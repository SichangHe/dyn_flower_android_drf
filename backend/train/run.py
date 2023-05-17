"""`fig_config` and code in `server` are copied from Flower Android example."""
from logging import getLogger
from multiprocessing.connection import Connection

from flwr.common import FitRes, Parameters, Scalar
from flwr.server import ServerConfig, start_server
from flwr.server.client_proxy import ClientProxy
from flwr.server.strategy import FedAvgAndroid
from flwr.server.strategy.aggregate import aggregate
from numpy.typing import NDArray
from train.models import ModelParams, TFLiteModel

PORT = 8080

logger = getLogger(__name__)


class FedAvgAndroidSave(FedAvgAndroid):
    model: TFLiteModel | None = None

    def aggregate_fit(
        self,
        server_round: int,
        results: list[tuple[ClientProxy, FitRes]],
        failures: list[tuple[ClientProxy, FitRes] | BaseException],
    ) -> tuple[Parameters | None, dict[str, Scalar]]:
        """Aggregate fit results using weighted average."""
        # This method is initially copied from `server/strategy/fedavg_android.py`
        # in the `flwr` repository.
        if not results:
            return None, {}
        # Do not aggregate if there are failures and failures are not accepted
        if not self.accept_failures and failures:
            return None, {}
        # Convert results
        weights_results = [
            (self.parameters_to_ndarrays(fit_res.parameters), fit_res.num_examples)
            for client, fit_res in results
        ]
        aggregated = aggregate(weights_results)
        self.save_params(aggregated)
        return self.ndarrays_to_parameters(aggregated), {}

    def save_params(self, params: list[NDArray]):
        if self.model is None:
            # Skip if no model corresponding to the parameters is specified.
            return

        to_save = ModelParams(params=params, tflite_model=self.model)
        try:
            to_save.save()
        except RuntimeError as err:
            logger.error(err)


def fit_config(server_round: int):
    """Return training configuration dict for each round.

    Keep batch size fixed at 32, perform two rounds of training with one
    local epoch, increase to two local epochs afterwards.
    """
    config = {
        "batch_size": 32,
        "local_epochs": 5,
    }
    return config


def server(model: TFLiteModel | None = None):
    # TODO: Make configurable.
    strategy = FedAvgAndroidSave(
        fraction_fit=1.0,
        fraction_evaluate=1.0,
        min_fit_clients=2,
        min_evaluate_clients=2,
        min_available_clients=2,
        evaluate_fn=None,
        on_fit_config_fn=fit_config,
        initial_parameters=None,
    )
    strategy.model = model

    # Start Flower server for 10 rounds of federated learning
    start_server(
        server_address=f"0.0.0.0:{PORT}",
        config=ServerConfig(num_rounds=10),
        strategy=strategy,
    )


def execute(conn: Connection):
    """Execute one instruction received."""
    kind, msg = conn.recv()
    if kind == "ping":
        logger.warning(f"Received `{msg}`.")
        conn.send("pong")
    elif kind == "server":
        logger.warning(f"Launching server with arguments `{msg}`.")
        if isinstance(msg, TFLiteModel):
            server(msg)
        else:
            if msg is not None:
                logger.error(f"Unknown argument `{msg}` for Flower server.")
            server()
        conn.send("done")
    else:
        logger.error(f"Unknown kind `{kind}` with message `{msg}`.")
        conn.send("cannot understand")


def run(conn: Connection):
    """Run a procedure that listens to instructions and executes them."""
    logger.warning("Runner started.")
    while True:
        try:
            execute(conn)
        except KeyboardInterrupt:
            break
