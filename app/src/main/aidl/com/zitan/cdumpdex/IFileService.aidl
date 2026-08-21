package com.zitan.cdumpdex;

interface IFileService {
    // Destroy method defined by Shizuku server
    void destroy() = 16777114;

    // Write file to specified path
    boolean writeFile(String path, String content) = 1;

    // Create directory
    boolean mkdir(String path) = 2;

    // Check if file exists
    boolean exists(String path) = 3;

    // Read file content
    String readFile(String path) = 4;

    // Execute shell command and return output
    String executeCommand(String command) = 5;
}
