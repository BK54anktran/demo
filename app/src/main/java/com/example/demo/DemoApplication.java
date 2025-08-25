package com.example.demo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

                List<QuicConnection.QuicVersion> supportedVersions = new ArrayList<>();
                supportedVersions.add(QuicConnection.QuicVersion.V1);

                ServerConnectionConfig serverConfig = ServerConnectionConfig.builder()
                                .maxIdleTimeoutInSeconds(30)
                                .retryRequired(true)
                                .connectionIdLength(8)
                                .maxOpenPeerInitiatedBidirectionalStreams(100) // ✅ cấp cho client mở tối đa 100 stream
                                .maxOpenPeerInitiatedUnidirectionalStreams(100) // tùy chọn
                                .build();

                ServerConnector serverConnector = ServerConnector.builder()
                                .withPort(port)
                                .withSupportedVersions(supportedVersions)
                                .withConfiguration(serverConfig)
                                .withCertificate(new FileInputStream(certFile), new FileInputStream(keyFile))
                                .withLogger(log)
                                .build();

                // Application Protocol "echo"
                // ApplicationProtocolConnectionFactory echoFactory = new
                // ApplicationProtocolConnectionFactory() {
                // @Override
                // public ApplicationProtocolConnection createConnection(String protocol,
                // QuicConnection connection) {
                // return new ApplicationProtocolConnection() {
                // @Override
                // public void acceptPeerInitiatedStream(QuicStream stream) {
                // new Thread(() -> {
                // try {
                // byte[] buf = stream.getInputStream().readAllBytes();
                // String msg = new String(buf, StandardCharsets.UTF_8);
                // System.out.println("Server received: " + msg);

                // stream.getOutputStream().write(("Echo: " + msg)
                // .getBytes(StandardCharsets.UTF_8));
                // stream.getOutputStream().flush();
                // stream.getOutputStream().close(); // đóng để client biết
                // // đã hết dữ liệu
                // } catch (Exception e) {
                // e.printStackTrace();
                // }
                // }).start();
                // }
                // };
                // }

                // @Override
                // public int maxConcurrentPeerInitiatedUnidirectionalStreams() {
                // return 100;
                // }

                // @Override
                // public int maxConcurrentPeerInitiatedBidirectionalStreams() {
                // return 100;
                // }
                // };

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
                                                                byte[] buf = stream.getInputStream().readAllBytes();
                                                                String msg = new String(buf, StandardCharsets.UTF_8);
                                                                System.out.println("Server received: " + msg);

                                                                // Echo lại
                                                                stream.getOutputStream().write(("Echo: " + msg)
                                                                                .getBytes(StandardCharsets.UTF_8));
                                                                stream.getOutputStream().flush();
                                                                stream.getOutputStream().close(); // ✅ báo client là
                                                                                                  // xong
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

                // chạy server ở thread riêng
                new Thread(() -> {
                        serverConnector.start();
                        log.info("Kwik QUIC server " + KwikVersion.getVersion() + " started on port " + port);
                }).start();

                // đợi server lên
                Thread.sleep(1000);

                // === CLIENT ===
                QuicClientConnection client = QuicClientConnection.newBuilder()
                                .uri(new URI("quic://localhost:" + port))
                                .applicationProtocol("echo")
                                .version(QuicConnection.QuicVersion.V1)
                                .noServerCertificateCheck()
                                .build();

                client.connect();
                if (client.isConnected()) {
                        System.out.println("QUIC handshake completed!");
                } else {
                        System.err.println("QUIC handshake failed!");
                }

                QuicStream stream = client.createStream(true);

                // Gửi
                BufferedOutputStream out = new BufferedOutputStream(stream.getOutputStream());
                out.write("Hello from QUIC client\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                stream.getOutputStream().close(); // ✅ quan trọng

                // ❌ đừng close ngay, để server đọc trước

                // Nhận
                byte[] buf = stream.getInputStream().readAllBytes();
                String reply = new String(buf, StandardCharsets.UTF_8);
                System.out.println("Client got echo: " + reply);
                client.close();

        }
}
