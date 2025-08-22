package com.example.demo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import tech.kwik.core.KwikVersion;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.FileLogger;
import tech.kwik.core.log.Logger;
import tech.kwik.core.log.SysOutLogger;
import tech.kwik.core.server.ApplicationProtocolConnectionFactory;
import tech.kwik.core.server.ServerConnectionConfig;
import tech.kwik.core.server.ServerConnector;

@SpringBootApplication
public class DemoApplication {

        private static void usageAndExit() {
                System.err.println("Usage: [--noRetry] cert file, cert key file, port number, www dir");
                System.err.println(
                                "   or: [--noRetry] key store file, key store (and key) password, port number, www dir");
                System.exit(1);
        }

        public static void main(String[] args) throws Exception {
                // try {
                // // If you want to see what happens under the hood, use a logger like this and
                // // add to builder with .logger(log)
                // SysOutLogger log = new SysOutLogger();
                // log.logPackets(true);
                // log.logInfo(true);

                // QuicClientConnection.Builder builder = QuicClientConnection.newBuilder();
                // QuicClientConnection connection = builder
                // .uri(new URI(args[0]))
                // // The early QUIC implementors choose "hq-interop" as the ALPN identifier for
                // // running HTTP 0.9 on top of QUIC,
                // // see https://github.com/quicwg/base-drafts/wiki/21st-Implementation-Draft
                // .applicationProtocol("hq-interop")
                // .build();

                // connection.connect();

                // QuicStream stream = connection.createStream(true);

                // BufferedOutputStream outputStream = new
                // BufferedOutputStream(stream.getOutputStream());
                // // HTTP 0.9 really is very simple: a GET request without any headers.
                // outputStream.write("GET / \r\n".getBytes(StandardCharsets.UTF_8));
                // outputStream.flush();

                // long transferred = stream.getInputStream()
                // .transferTo(new FileOutputStream("kwik_client_output"));

                // connection.close();

                // System.out.println("Received " + transferred + " bytes.");
                // } catch (java.net.URISyntaxException | java.io.IOException e) {
                // e.printStackTrace();
                // }

                // List<String> args = new ArrayList<>(Arrays.asList(rawArgs));
                if (args.length < 4) {
                        usageAndExit();
                }

                System.out.println(args.length + " arguments provided: " + Arrays.toString(args));

                boolean withRetry = true;
                if (args[0].equals("--noRetry")) {
                        withRetry = false;
                        System.out.println("Retry disabled");
                        args[0] = null;
                }

                if (args.length < 4 || Arrays.stream(args).anyMatch(arg -> arg != null && arg.startsWith("-"))) {
                        usageAndExit();
                }

                Logger log;
                File logDir = new File("/logs");
                if (logDir.exists() && logDir.isDirectory() && logDir.canWrite()) {
                        log = new FileLogger(new File(logDir, "kwikserver.log"));
                } else {
                        log = new SysOutLogger();
                }
                log.timeFormat(Logger.TimeFormat.Long);
                log.logWarning(true);
                log.logInfo(true);
                log.logStream(true);

                File certificateFile = null;
                File certificateKeyFile = null;
                KeyStore keyStore = null;
                String keyStorePassword = null;

                if (new File(args[0]).exists() && new File(args[1]).exists()) {
                        certificateFile = new File(args[0]);
                        certificateKeyFile = new File(args[1]);
                } else if (new File(args[0]).exists()) {
                        File keyStoreFile = new File(args[0]);
                        keyStorePassword = args[1];
                        keyStore = KeyStore.getInstance(keyStoreFile, keyStorePassword.toCharArray());
                } else {
                        if (new File(args[1]).exists()) {
                                System.err.println("Certificate / Keystore file does not exist or is not readable.");
                        } else {
                                System.err.println("Key file does not exist or is not readable.");
                        }
                        System.exit(1);
                }

                int port = Integer.parseInt(args[2]);

                File wwwDir = new File(args[3]);
                if (!wwwDir.exists() || !wwwDir.isDirectory() || !wwwDir.canRead()) {
                        System.err.println("Cannot read www dir '" + wwwDir + "'");
                        System.exit(1);
                }

                List<QuicConnection.QuicVersion> supportedVersions = new ArrayList<>();
                supportedVersions.add(QuicConnection.QuicVersion.V1);
                supportedVersions.add(QuicConnection.QuicVersion.V2);

                ServerConnectionConfig serverConnectionConfig = ServerConnectionConfig.builder()
                                .maxIdleTimeoutInSeconds(30)
                                .maxUnidirectionalStreamBufferSize(1_000_000)
                                .maxBidirectionalStreamBufferSize(1_000_000)
                                .maxConnectionBufferSize(10_000_000)
                                .maxOpenPeerInitiatedUnidirectionalStreams(10)
                                .maxOpenPeerInitiatedBidirectionalStreams(100)
                                .retryRequired(withRetry)
                                .connectionIdLength(8)
                                .build();

                ServerConnector.Builder builder = ServerConnector.builder()
                                .withPort(port)
                                .withSupportedVersions(supportedVersions)
                                .withConfiguration(serverConnectionConfig)
                                .withLogger(log);

                if (certificateFile != null) {
                        builder.withCertificate(new FileInputStream(certificateFile),
                                        new FileInputStream(certificateKeyFile));
                } else {
                        String alias = keyStore.aliases().nextElement();
                        System.out.println("Using certificate with alias " + alias + " from keystore");
                        builder.withKeyStore(keyStore, alias, keyStorePassword.toCharArray());
                }

                ServerConnector serverConnector = builder.build();

                // registerHttp3(serverConnector, wwwDir, supportedVersions, log);

                serverConnector.start();
                log.info("Kwik server " + KwikVersion.getVersion() + " started; supported application protocols: "
                                + serverConnector.getRegisteredApplicationProtocols());
                SpringApplication.run(DemoApplication.class, args);
        }

        private static void registerHttp3(ServerConnector serverConnector, File wwwDir,
                        List<QuicConnection.QuicVersion> supportedVersions, Logger log) {
                ApplicationProtocolConnectionFactory http3ApplicationProtocolConnectionFactory = null;

                try {
                        // If flupke server plugin is on classpath, load the http3 connection factory
                        // class.
                        http3ApplicationProtocolConnectionFactory = http3FlupkeOld(wwwDir);
                        if (http3ApplicationProtocolConnectionFactory == null) {
                                http3ApplicationProtocolConnectionFactory = http3FlupkeNew(wwwDir);
                        }
                        log.info("Loading Flupke H3 server plugin");

                        serverConnector.registerApplicationProtocol("h3", http3ApplicationProtocolConnectionFactory);
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                                | IllegalAccessException | InvocationTargetException e) {
                        log.error("No H3 protocol: Flupke plugin not found.");
                        System.exit(1);
                }
        }

        private static ApplicationProtocolConnectionFactory http3FlupkeOld(File wwwDir) {
                try {
                        Class<?> http3FactoryClass = DemoApplication.class.getClassLoader()
                                        .loadClass("net.luminis.http3.server.Http3ApplicationProtocolFactory");
                        return (ApplicationProtocolConnectionFactory) http3FactoryClass
                                        .getDeclaredConstructor(new Class[] { File.class }).newInstance(wwwDir);
                } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                                | IllegalAccessException | InvocationTargetException e) {
                        System.out.println("Old Flupke plugin not found");
                        return null;
                }
        }

        private static ApplicationProtocolConnectionFactory http3FlupkeNew(File wwwDir)
                        throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException,
                        InstantiationException, IllegalAccessException {
                Class<?> http3FactoryClass = DemoApplication.class.getClassLoader().loadClass(
                                "tech.kwik.flupke.sample.kwik.Http3SimpleFileServerApplicationProtocolConnectionFactory");
                return (ApplicationProtocolConnectionFactory) http3FactoryClass
                                .getDeclaredConstructor(new Class[] { File.class }).newInstance(wwwDir);
        }

}
