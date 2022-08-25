package app;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.com.google.common.util.concurrent.Uninterruptibles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Application {

  private static final Logger LOG = LogManager.getLogger(Application.class);

  private static final String MYSQL_ROOT_PASSWORD = "test1234#";
  private static final String CLUSTER_NAME = "pxc";

  public void start() throws Exception {
    Path cert = createCerts();

    Network network = setupNetwork();

    GenericContainer<?> perconaNode1 = startPerconaNode(network, cert, "node1", null);
    GenericContainer<?> perconaNode2 = startPerconaNode(network, cert, "node2", "node1");
    GenericContainer<?> perconaNode3 = startPerconaNode(network, cert, "node3", "node1");
//    GenericContainer<?> proxySqlContainer = startProxySqlContainer(network);


    LOG.info("\uD83D\uDE80 Percona Cluster and ProxySQL are ready");

    Uninterruptibles.awaitUninterruptibly(new CountDownLatch(1));
  }

  private Path createCerts() throws Exception {
    LOG.info("\u231B Creating Percona server certificates...");
    Path cert = Files.createTempDirectory("cert");
    cert.toFile().deleteOnExit();
    Files.setPosixFilePermissions(cert, PosixFilePermissions.fromString("rwxrwxrwx"));
    GenericContainer<?> container = new GenericContainer<>("percona/percona-xtradb-cluster:8.0")
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("mysql_ssl_rsa_setup")))
      .withFileSystemBind(cert.toAbsolutePath().toString(), "/cert")
      .withCommand("mysql_ssl_rsa_setup", "-d", "/cert")
      .waitingFor(Wait.forLogMessage(".*-----.*\\n", 3));
    container.start();
    LOG.info("\uD83D\uDE80 Percona server certificates created");
    return cert;
  }

  private Network setupNetwork() {
    return Network.builder().driver("bridge").build();
  }

  private GenericContainer<?> startPerconaNode(Network network, Path cert, String name, String join) {
    LOG.info("\u231B Starting Percona Cluster " + name + "...");
    GenericContainer<?> container = new GenericContainer<>("percona/percona-xtradb-cluster:8.0")
      .withEnv("MYSQL_ROOT_PASSWORD", MYSQL_ROOT_PASSWORD)
      .withEnv("CLUSTER_NAME", CLUSTER_NAME)
      .withNetwork(network)
      .withNetworkAliases(name)
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(name)))
      .withFileSystemBind(cert.toAbsolutePath().toString(), "/cert")
      .withClasspathResourceMapping("custom.cnf", "/etc/percona-xtradb-cluster.conf.d/custom.cnf", BindMode.READ_ONLY)
      .waitingFor(Wait.forLogMessage(".*Synchronized with group, ready for connections.*\\n", 1));
    if (join != null) {
      container.withEnv("CLUSTER_JOIN", join);
    }
    container.start();
    Uninterruptibles.sleepUninterruptibly(15, TimeUnit.SECONDS);
    LOG.info("\uD83D\uDE80 Percona Cluster " + name + " started");
    return container;
  }

  private GenericContainer<?> startProxySqlContainer(Network network) {
    return null;
  }

  public static void main(String[] args) {
    try {
      new Application().start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
