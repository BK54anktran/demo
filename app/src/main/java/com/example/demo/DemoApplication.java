package com.example.demo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import tech.kwik.core.KwikVersion;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.Logger;
import tech.kwik.core.log.SysOutLogger;
import tech.kwik.core.server.ApplicationProtocolConnection;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

@SpringBootApplication
public class DemoApplication {

        private static void usageAndExit() {
                System.err.println("Usage: cert.pem key.pem port wwwDir");
                System.exit(1);
        }

        public static void main(String[] args) throws Exception {
                if (args.length < 4) {
                        usageAndExit();
                }

                System.out.println(args.length + " arguments provided: " + Arrays.toString(args));

                // Setup logger
                Logger log = new SysOutLogger();
                log.timeFormat(Logger.TimeFormat.Long);
                log.logWarning(true);
                log.logInfo(true);
                log.logStream(true);

                File certFile = new File(args[0]);
                File keyFile = new File(args[1]);
                int port = Integer.parseInt(args[2]);
                File wwwDir = new File(args[3]);

                if (!certFile.exists() || !keyFile.exists()) {
                        System.err.println("Cert/Key file not found.");
                        System.exit(1);
                }
                if (!wwwDir.exists() || !wwwDir.isDirectory()) {
                        System.err.println("www dir invalid.");
                        System.exit(1);
                }

                // QUIC version
                List<QuicConnection.QuicVersion> supportedVersions = new ArrayList<>();
                supportedVersions.add(QuicConnection.QuicVersion.V1);

                // Server config
                ServerConnectionConfig serverConfig = ServerConnectionConfig.builder()
                                .maxIdleTimeoutInSeconds(30)
                                .retryRequired(true)
                                .connectionIdLength(8)
                                .maxOpenPeerInitiatedBidirectionalStreams(100) // allow client up to 100 bidirectional
                                                                               // streams
                                .maxOpenPeerInitiatedUnidirectionalStreams(100) // allow client up to 100 unidirectional
                                                                                // streams
                                .build();

                // Create server connector
                ServerConnector serverConnector = ServerConnector.builder()
                                .withPort(port)
                                .withSupportedVersions(supportedVersions)
                                .withConfiguration(serverConfig)
                                .withCertificate(new FileInputStream(certFile), new FileInputStream(keyFile))
                                .withLogger(log)
                                .build();

                // Register application protocol "echo"
                serverConnector.registerApplicationProtocol("echo", new ApplicationProtocolConnectionFactory() {
                        @Override
                        public ApplicationProtocolConnection createConnection(String protocol,
                                        QuicConnection connection) {
                                System.out.println("✅ New app protocol connection: " + protocol);

                                return new ApplicationProtocolConnection() {
                                        @Override
                                        public void acceptPeerInitiatedStream(QuicStream stream) {
                                                System.out.println("✅ acceptPeerInitiatedStream called!");
                                                new Thread(() -> {
                                                        try {
                                                                // Read all bytes sent by client
                                                                byte[] buf = stream.getInputStream().readAllBytes();
                                                                String msg = new String(buf, StandardCharsets.UTF_8);
                                                                System.out.println("Server received: " + msg);

                                                                // Echo back to client
                                                                stream.getOutputStream().write(("Echo: " + msg)
                                                                                .getBytes(StandardCharsets.UTF_8));
                                                                stream.getOutputStream().flush();

                                                                // Important: close output to signal client that
                                                                // response is complete
                                                                stream.getOutputStream().close();
                                                        } catch (Exception e) {
                                                                e.printStackTrace();
                                                        }
                                                }).start();
                                        }
                                };
                        }

                        @Override
                        public int maxConcurrentPeerInitiatedUnidirectionalStreams() {
                                return 100;
                        }

                        @Override
                        public int maxConcurrentPeerInitiatedBidirectionalStreams() {
                                return 100;
                        }
                });

                // Run server in separate thread
                new Thread(() -> {
                        serverConnector.start();
                        log.info("Kwik QUIC server " + KwikVersion.getVersion() + " started on port " + port);
                }).start();

                // Wait for server to start
                Thread.sleep(1000);

                // === CLIENT ===
                QuicClientConnection client = QuicClientConnection.newBuilder()
                                .uri(new URI("quic://localhost:" + port))
                                .applicationProtocol("echo")
                                .version(QuicConnection.QuicVersion.V1)
                                .noServerCertificateCheck() // WARNING: disables TLS verification
                                .build();

                client.connect();
                if (client.isConnected()) {
                        System.out.println("QUIC handshake completed!");
                } else {
                        System.err.println("QUIC handshake failed!");
                }

                // Open new bidirectional stream
                QuicStream stream = client.createStream(true);

                // Send data
                BufferedOutputStream out = new BufferedOutputStream(stream.getOutputStream());
                out.write("Hello from QUIC client\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                // Important: close output to notify server no more data is coming
                stream.getOutputStream().close();

                // Receive echo from server
                byte[] buf = stream.getInputStream().readAllBytes();
                String reply = new String(buf, StandardCharsets.UTF_8);
                System.out.println("Client got echo: " + reply);

                // Close client connection
                client.close();
        }
}
