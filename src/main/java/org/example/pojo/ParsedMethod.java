package org.example.pojo;

// Simple POJO to hold extracted method info
public class ParsedMethod {
    public String name;
    public String signature;
    public String filePath;
    public int startLine;
    public int endLine;
    public String codeSnippet;
    public String language = "java"; // Add language field

    // Constructor, getters/setters omitted for brevity
}
