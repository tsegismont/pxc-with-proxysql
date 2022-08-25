package app;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.shaded.org.apache.commons.lang3.time.StopWatch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testcontainers.shaded.com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static org.testcontainers.shaded.com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;

public class Application {

  private static final Logger LOG = LogManager.getLogger(Application.class);

  private static final String CLUSTER_NAME = "percona-cluster";

  private static final String MYSQL_ROOT_PASSWORD = "root1234#";

  private static final String MONITORING_USER = "proxysql";
  private static final String MONITORING_USER_PASSWORD = "proxysql1234#";

  private static final String CLIENT_USER = "dbuser";
  private static final String CLIENT_USER_PASSWORD = "dbuser1234#";

  public void start() throws Exception {
    Path cert = createCerts();

    Network network = setupNetwork();

    startPerconaNode(network, cert, "node1", false);
    startPerconaNode(network, cert, "node2", true);
    GenericContainer<?> perconaNode3 = startPerconaNode(network, cert, "node3", true);

    createBackendUsers(perconaNode3);

    GenericContainer<?> proxySqlContainer = startProxySqlContainer(network);

    configureProxySql(proxySqlContainer);

    Integer mappedPort = proxySqlContainer.getMappedPort(6033);
    LOG.info("\uD83D\uDE80 Ready to receive connections with Vert.x >>> mysql://{}:{}@127.0.0.1:{}", CLIENT_USER, CLIENT_USER_PASSWORD, mappedPort);

    awaitUninterruptibly(new CountDownLatch(1));
  }

  private Path createCerts() throws Exception {
    LOG.info("\u231B Creating Percona server certificates...");
    StopWatch watch = StopWatch.createStarted();
    Path cert = Files.createTempDirectory("cert");
    cert.toFile().deleteOnExit();
    Files.setPosixFilePermissions(cert, PosixFilePermissions.fromString("rwxrwxrwx"));
    GenericContainer<?> container = new GenericContainer<>("percona/percona-xtradb-cluster:8.0")
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("mysql_ssl_rsa_setup")))
      .withFileSystemBind(cert.toAbsolutePath().toString(), "/cert")
      .withCommand("mysql_ssl_rsa_setup", "-d", "/cert")
      .waitingFor(Wait.forLogMessage(".*-----.*\\n", 3));
    container.start();
    LOG.info("\uD83D\uDE80 Percona server certificates created in {}", watch.formatTime());
    return cert;
  }

  private Network setupNetwork() {
    return Network.builder().driver("bridge").build();
  }

  private GenericContainer<?> startPerconaNode(Network network, Path cert, String name, boolean join) {
    LOG.info("\u231B Starting Percona " + name + "...");
    StopWatch watch = StopWatch.createStarted();
    GenericContainer<?> container = new GenericContainer<>("percona/percona-xtradb-cluster:8.0")
      .withEnv("MYSQL_ROOT_PASSWORD", MYSQL_ROOT_PASSWORD)
      .withEnv("CLUSTER_NAME", CLUSTER_NAME)
      .withNetwork(network)
      .withNetworkAliases(name)
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(name)))
      .withFileSystemBind(cert.toAbsolutePath().toString(), "/cert")
      .withCopyToContainer(Transferable.of("""
        [mysqld]
        ssl-ca = /cert/ca.pem
        ssl-cert = /cert/server-cert.pem
        ssl-key = /cert/server-key.pem

        [client]
        ssl-ca = /cert/ca.pem
        ssl-cert = /cert/client-cert.pem
        ssl-key = /cert/client-key.pem

        [sst]
        encrypt = 4
        ssl-ca = /cert/ca.pem
        ssl-cert = /cert/server-cert.pem
        ssl-key = /cert/server-key.pem
        """.stripIndent()), "/etc/percona-xtradb-cluster.conf.d/custom.cnf");
    String logMsg = ".*Synchronized with group, ready for connections.*\\n";
    if (join) {
      container
        .withEnv("CLUSTER_JOIN", "node1").waitingFor(Wait.forLogMessage(logMsg, 1));
    } else {
      container
        .waitingFor(Wait.forLogMessage(logMsg, 2));
    }
    container.start();
    LOG.info("\uD83D\uDE80 Percona " + name + " started in {}", watch.formatTime());
    return container;
  }

  private static void createBackendUsers(GenericContainer<?> container) throws Exception {
    LOG.info("\u231B Creating backend users...");
    StopWatch watch = StopWatch.createStarted();
    execStatement(container, "root", MYSQL_ROOT_PASSWORD, 3306, format(
      "CREATE USER '%s'@'%%' IDENTIFIED BY '%s'"
      , MONITORING_USER, MONITORING_USER_PASSWORD));
    execStatement(container, "root", MYSQL_ROOT_PASSWORD, 3306, format(
      "GRANT ALL ON *.* TO '%s'@'%%'"
      , MONITORING_USER));
    execStatement(container, "root", MYSQL_ROOT_PASSWORD, 3306, format(
      "CREATE USER '%s'@'%%' IDENTIFIED BY '%s'"
      , CLIENT_USER, CLIENT_USER_PASSWORD));
    execStatement(container, "root", MYSQL_ROOT_PASSWORD, 3306, format(
      "GRANT ALL ON *.* TO '%s'@'%%'"
      , CLIENT_USER));
    LOG.info("\uD83D\uDE80 Backend users created in {}", watch.formatTime());
  }

  private static void execStatement(GenericContainer<?> container, String user, String password, int port, String statement) throws Exception {
    LOG.debug("Executing statement: " + statement);
    Container.ExecResult result = container.execInContainer("mysql", "-u", user, "-p" + password, "-h", "127.0.0.1", "-P", String.valueOf(port), "-e", statement);
    if (result.getExitCode() != 0) {
      throw new RuntimeException("Failed to execute statement: " + statement + "\n" + result.getStderr());
    }
  }

  private GenericContainer<?> startProxySqlContainer(Network network) {
    LOG.info("\u231B Starting ProxySQL...");
    StopWatch watch = StopWatch.createStarted();
    GenericContainer<?> container = new GenericContainer<>("proxysql/proxysql")
      .withNetwork(network)
      .withExposedPorts(6032, 6033, 6070)
      .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("proxysql")))
      .waitingFor(Wait.forLogMessage(".*Latest ProxySQL version available.*\\n", 1));
    container.start();
    sleepUninterruptibly(5, SECONDS);
    LOG.info("\uD83D\uDE80 ProxySQL started in {}", watch.formatTime());
    return container;
  }

  private static void configureProxySql(GenericContainer<?> proxy) throws Exception {
    LOG.info("\u231B Configuring ProxySQL...");
    StopWatch watch = StopWatch.createStarted();
    for (String node : List.of("node1", "node2", "node3")) {
      execStatement(proxy, "admin", "admin", 6032, format(
        "INSERT INTO mysql_servers(hostgroup_id, hostname, port) VALUES (0,'%s',3306)"
        , node));
    }
    execStatement(proxy, "admin", "admin", 6032, format(
      "UPDATE global_variables SET variable_value='%s' WHERE variable_name='mysql-monitor_username'"
      , MONITORING_USER));
    execStatement(proxy, "admin", "admin", 6032, format(
      "UPDATE global_variables SET variable_value='%s' WHERE variable_name='mysql-monitor_password'"
      , MONITORING_USER_PASSWORD));
    execStatement(proxy, "admin", "admin", 6032,
      "LOAD MYSQL VARIABLES TO RUNTIME"
    );
    execStatement(proxy, "admin", "admin", 6032,
      "LOAD MYSQL SERVERS TO RUNTIME"
    );
    execStatement(proxy, "admin", "admin", 6032, format(
      "INSERT INTO mysql_users (username,password) VALUES ('%s','%s')"
      , CLIENT_USER, CLIENT_USER_PASSWORD));
    execStatement(proxy, "admin", "admin", 6032,
      "LOAD MYSQL USERS TO RUNTIME"
    );
    LOG.info("\uD83D\uDE80 ProxySQL configured in {}", watch.formatTime());
  }

  public static void main(String[] args) {
    try {
      new Application().start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
