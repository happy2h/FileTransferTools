package com.example.ftc.registry;

import com.example.ftc.handler.CommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for command handlers
 *
 * Auto-collects all CommandHandler beans via Spring dependency injection
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, CommandHandler<?, ?>> handlers = new ConcurrentHashMap<>();

    @Autowired
    public CommandRegistry(List<CommandHandler<?, ?>> handlerList) {
        for (CommandHandler<?, ?> handler : handlerList) {
            String commandKey = handler.command().toUpperCase().trim();
            handlers.put(commandKey, handler);
            log.info("Registered command handler: {} -> {}", commandKey, handler.getClass().getSimpleName());
        }
    }

    /**
     * Get handler for the specified command
     *
     * @param command Command name (case-insensitive, will be trimmed)
     * @return CommandHandler or null if not found
     */
    public CommandHandler<?, ?> getHandler(String command) {
        String commandKey = command.toUpperCase().trim();
        return handlers.get(commandKey);
    }
}
