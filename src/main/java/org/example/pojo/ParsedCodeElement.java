// src/main/java/org/example/pojo/ParsedCodeElement.java

package org.example.pojo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Include non-null fields only to keep JSON clean
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParsedCodeElement {
    public String name;
    public String signature; // For Java methods, null/empty for proto msg/enum
    @JsonProperty("code_type") // Map Java field name to JSON field name
    public String codeType; // e.g., "Method", "Message", "Enum"
    public String docstring; // Extracted documentation comment
    public int line; // Starting line of the declaration/signature
    @JsonProperty("line_from")
    public int lineFrom; // Starting line of the element (including doc comments)
    @JsonProperty("line_to")
    public int lineTo; // Ending line of the element
    public Context context;

    public static class Context {
        public String module; // Java package name or Proto package
        @JsonProperty("file_path")
        public String filePath;
        @JsonProperty("file_name")
        public String fileName;
        @JsonProperty("class_name") // Enclosing class/interface/record for Java methods
        public String className;
        public String snippet; // The code block itself
    }
}