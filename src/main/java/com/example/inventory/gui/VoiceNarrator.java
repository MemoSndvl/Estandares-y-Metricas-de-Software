package com.example.inventory.gui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class VoiceNarrator implements AutoCloseable {
    private static final String POWERSHELL_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            try {
                [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
                $speaker = New-Object -ComObject SAPI.SpVoice
                [Console]::Out.WriteLine('READY')
                while ($null -ne ($line = [Console]::In.ReadLine())) {
                    if ($line -eq '__STOP__') { break }
                    $speaker.Speak($line, 3) | Out-Null
                }
            } catch {
                [Console]::Error.WriteLine($_.Exception.Message)
                exit 1
            }
            """;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "inventory-voice-narrator");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<String> latestMessage = new AtomicReference<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<String> errorHandler;

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    VoiceNarrator(Consumer<String> errorHandler) {
        this.errorHandler = errorHandler;
    }

    static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    void speak(String message) {
        if (closed.get() || message == null || message.isBlank()) {
            return;
        }
        latestMessage.set(message);
        if (drainScheduled.compareAndSet(false, true)) {
            executor.execute(this::drainMessages);
        }
    }

    private void drainMessages() {
        try {
            String message;
            while (!closed.get() && (message = latestMessage.getAndSet(null)) != null) {
                startProcessIfNeeded();
                writer.write(message);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException exception) {
            stopProcess();
            if (closed.compareAndSet(false, true)) {
                errorHandler.accept("No se pudo iniciar o utilizar la voz de Windows: " + exception.getMessage());
            }
        } finally {
            drainScheduled.set(false);
            if (!closed.get() && latestMessage.get() != null
                    && drainScheduled.compareAndSet(false, true)) {
                executor.execute(this::drainMessages);
            }
        }
    }

    private void startProcessIfNeeded() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                POWERSHELL_SCRIPT);
        builder.redirectErrorStream(true);
        process = builder.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        String readiness = reader.readLine();
        if (!"READY".equals(readiness)) {
            throw new IOException(readiness == null
                    ? "El servicio de voz se cerró durante el inicio."
                    : readiness);
        }
    }

    private void stopProcess() {
        if (process == null) {
            return;
        }
        try {
            if (process.isAlive() && writer != null) {
                writer.write("__STOP__");
                writer.newLine();
                writer.flush();
                process.getOutputStream().close();
                if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy();
                }
            }
        } catch (IOException exception) {
            process.destroy();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroy();
        } finally {
            process = null;
            reader = null;
            writer = null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        latestMessage.set(null);
        if (process != null) {
            process.destroy();
        }
        executor.execute(this::stopProcess);
        executor.shutdown();
    }
}
