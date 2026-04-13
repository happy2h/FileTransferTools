package com.example.ftc.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Decoded command frame containing command name and body bytes
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommandFrame {
    private String command;   // Trimmed command string
    private byte[] body;      // JSON body bytes
}
