package com.example.ftc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FileTransferClient Application
 *
 * TCP Server listening on port 7111, receiving commands from remote services
 */
@SpringBootApplication
public class FtcApplication {

    public static void main(String[] args) {
        SpringApplication.run(FtcApplication.class, args);
    }
}
