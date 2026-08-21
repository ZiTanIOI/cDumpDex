package com.zitan.cdumpdex;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Log {
    private final String logPath;
    private final File logFile;

    public Log(String filePath) {
        logPath = filePath;
        logFile = new File(filePath);
    }

    public Log(File logFile) {
        logPath = logFile.getAbsolutePath();
        this.logFile = logFile;
    }

    public void writeLog(String message, String level) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
            DateFormat formatter = SimpleDateFormat.getInstance();
            Date date = new Date(System.currentTimeMillis());
            writer.write("[" + formatter.format(date) + "] " + level + ": " + message + "\n");
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void d(String info) {
        writeLog(info, "D");
    }

    public void e(String info) {
        writeLog(info, "E");
    }

    public void e(Throwable throwable) {
        e(android.util.Log.getStackTraceString(throwable));
    }

    public String getLogPath() {
        return logPath;
    }
}
