package com.example.demo;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import tech.kwik.core.log.SysOutLogger;

@SpringBootApplication
public class DemoApplication {
        public static void main(String[] args) {
                try {
                        // If you want to see what happens under the hood, use a logger like this and
                        // add to builder with .logger(log)
                        SysOutLogger log = new SysOutLogger();
                        log.logPackets(true);
                        log.logInfo(true);

                        QuicClientConnection.Builder builder = QuicClientConnection.newBuilder();
                        QuicClientConnection connection = builder
                                        .uri(new URI(args[0]))
                                        // The early QUIC implementors choose "hq-interop" as the ALPN identifier for
                                        // running HTTP 0.9 on top of QUIC,
                                        // see https://github.com/quicwg/base-drafts/wiki/21st-Implementation-Draft
                                        .applicationProtocol("hq-interop")
                                        .build();

                        connection.connect();

                        QuicStream stream = connection.createStream(true);

                        BufferedOutputStream outputStream = new BufferedOutputStream(stream.getOutputStream());
                        // HTTP 0.9 really is very simple: a GET request without any headers.
                        outputStream.write("GET / \r\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();

                        long transferred = stream.getInputStream()
                                        .transferTo(new FileOutputStream("kwik_client_output"));

                        connection.close();

                        System.out.println("Received " + transferred + " bytes.");
                } catch (java.net.URISyntaxException | java.io.IOException e) {
                        e.printStackTrace();
                }
                SpringApplication.run(DemoApplication.class, args);
        }
}
