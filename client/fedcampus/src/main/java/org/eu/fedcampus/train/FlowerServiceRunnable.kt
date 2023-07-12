package org.eu.fedcampus.train

import android.util.Log
import com.google.protobuf.ByteString
import flwr.android_client.ClientMessage
import flwr.android_client.FlowerServiceGrpc
import flwr.android_client.Parameters
import flwr.android_client.Scalar
import flwr.android_client.ServerMessage
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eu.fedcampus.train.db.TFLiteModel
import org.eu.fedcampus.train.helpers.assertIntsEqual
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

/**
 * Start communication with Flower server and training in the background.
 * Note: constructing an instance of this class **immediately** starts training.
 * @param flowerServerChannel Channel already connected to Flower server.
 * @param callback Called with information on training events.
 */
class FlowerServiceRunnable<X : Any, Y : Any> @Throws constructor(
    val flowerServerChannel: ManagedChannel,
    val train: Train<X, Y>,
    val model: TFLiteModel,
    val flowerClient: FlowerClient<X, Y>,
    val callback: (String) -> Unit
) : AutoCloseable {
    private val scope = MainScope()
    private val sampleSize: Int
        get() = flowerClient.trainingSamples.size
    val finishLatch = CountDownLatch(1)

    val asyncStub = FlowerServiceGrpc.newStub(flowerServerChannel)!!
    val requestObserver = asyncStub.join(object : StreamObserver<ServerMessage> {
        override fun onNext(msg: ServerMessage) = try {
            handleMessage(msg)
        } catch (err: Throwable) {
            logStacktrace(err)
        }

        override fun onError(err: Throwable) {
            logStacktrace(err)
            close()
        }

        override fun onCompleted() {
            close()
            Log.d(TAG, "Done")
        }
    })!!

    @Throws
    fun handleMessage(message: ServerMessage) {
        val (clientMessage, keepGoing) = if (message.hasGetParametersIns()) {
            handleGetParamsIns()
        } else if (message.hasFitIns()) {
            handleFitIns(message)
        } else if (message.hasEvaluateIns()) {
            handleEvaluateIns(message)
        } else if (message.hasReconnectIns()) {
            handleReconnectIns()
        } else {
            throw Error("Unknown client message $message.")
        }
        requestObserver.onNext(clientMessage)
        if (!keepGoing) {
            requestObserver.onCompleted()
        }
        callback("Response sent to the server")
    }

    @Throws
    fun handleGetParamsIns(): Pair<ClientMessage, Boolean> {
        Log.d(TAG, "Handling GetParameters")
        callback("Handling GetParameters message from the server.")
        return weightsAsProto(weightsByteBuffers()) to true
    }

    @Throws
    fun handleFitIns(message: ServerMessage): Pair<ClientMessage, Boolean> {
        Log.d(TAG, "Handling FitIns")
        callback("Handling Fit request from the server.")
        val start = if (train.telemetry) System.currentTimeMillis() else null
        val layers = message.fitIns.parameters.tensorsList
        assertIntsEqual(layers.size, model.layers_sizes.size)
        val epochConfig = message.fitIns.configMap.getOrDefault(
            "local_epochs", Scalar.newBuilder().setSint64(1).build()
        )!!
        val epochs = epochConfig.sint64.toInt()
        val newWeights = weightsFromLayers(layers)
        flowerClient.updateParameters(newWeights.toTypedArray())
        flowerClient.fit(epochs, lossCallback = { callback("Average loss: ${it.average()}.") })
        if (start != null) {
            val end = System.currentTimeMillis()
            scope.launch { train.fitInsTelemetry(start, end) }
        }
        return fitResAsProto(weightsByteBuffers(), sampleSize) to true
    }

    @Throws
    fun handleEvaluateIns(message: ServerMessage): Pair<ClientMessage, Boolean> {
        Log.d(TAG, "Handling EvaluateIns")
        callback("Handling Evaluate request from the server")
        val start = if (train.telemetry) System.currentTimeMillis() else null
        val layers = message.evaluateIns.parameters.tensorsList
        assertIntsEqual(layers.size, model.layers_sizes.size)
        val newWeights = weightsFromLayers(layers)
        flowerClient.updateParameters(newWeights.toTypedArray())
        val (loss, accuracy) = flowerClient.evaluate()
        callback("Test Accuracy after this round = $accuracy")
        if (start != null) {
            val end = System.currentTimeMillis()
            scope.launch { train.evaluateInsTelemetry(start, end, loss, accuracy, sampleSize) }
        }
        return evaluateResAsProto(loss, sampleSize) to true
    }

    @Throws
    fun handleReconnectIns(): Pair<ClientMessage, Boolean> {
        Log.d(TAG, "Handling ReconnectIns")
        callback("Handling Reconnection request from the server")
        return ClientMessage.newBuilder()
            .setDisconnectRes(ClientMessage.DisconnectRes.newBuilder().build()).build() to false
    }

    private fun weightsByteBuffers() = flowerClient.getParameters()

    private fun weightsFromLayers(layers: List<ByteString>) =
        layers.map { ByteBuffer.wrap(it.toByteArray()) }

    private fun logStacktrace(err: Throwable) {
        Log.e(TAG, err.stackTraceToString())
    }

    override fun close() {
        if (finishLatch.count > 0) {
            finishLatch.countDown()
            flowerServerChannel.shutdown()
        }
    }

    companion object {
        private const val TAG = "Flower Service Runnable"
    }
}

fun weightsAsProto(weights: Array<ByteBuffer>): ClientMessage {
    val layers = weights.map { ByteString.copyFrom(it) }
    val p = Parameters.newBuilder().addAllTensors(layers).setTensorType("ND").build()
    val res = ClientMessage.GetParametersRes.newBuilder().setParameters(p).build()
    return ClientMessage.newBuilder().setGetParametersRes(res).build()
}

fun fitResAsProto(weights: Array<ByteBuffer>, training_size: Int): ClientMessage {
    val layers: MutableList<ByteString> = ArrayList()
    for (weight in weights) {
        layers.add(ByteString.copyFrom(weight))
    }
    val p = Parameters.newBuilder().addAllTensors(layers).setTensorType("ND").build()
    val res =
        ClientMessage.FitRes.newBuilder().setParameters(p).setNumExamples(training_size.toLong())
            .build()
    return ClientMessage.newBuilder().setFitRes(res).build()
}

fun evaluateResAsProto(accuracy: Float, testing_size: Int): ClientMessage {
    val res = ClientMessage.EvaluateRes.newBuilder().setLoss(accuracy)
        .setNumExamples(testing_size.toLong()).build()
    return ClientMessage.newBuilder().setEvaluateRes(res).build()
}

/**
 * @param address Address of the gRPC server, like "dns:///$host:$port".
 */
suspend fun createChannel(address: String, useTLS: Boolean = false): ManagedChannel {
    val channelBuilder =
        ManagedChannelBuilder.forTarget(address).maxInboundMessageSize(HUNDRED_MEBIBYTE)
    if (!useTLS) {
        channelBuilder.usePlaintext()
    }
    return withContext(Dispatchers.IO) {
        channelBuilder.build()
    }
}

const val HUNDRED_MEBIBYTE = 100 * 1024 * 1024
