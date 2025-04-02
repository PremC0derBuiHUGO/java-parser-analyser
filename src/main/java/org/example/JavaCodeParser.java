package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.pojo.ParsedClass;
import org.example.pojo.ParsedMethod;

import java.io.BufferedReader; // Now needed for proto parsing
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets; // Specify charset
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher; // Still useful for finding start lines
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class JavaCodeParser {

    // --- Configuration ---
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final ParserConfiguration parserConfiguration = new ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private static final String OUTPUT_JSON_FILE = "output.json";
    private static final String ERROR_LOG_FILE = "errors.log";

    private static final Set<String> EXCLUDED_PATHS = Set.of(
        File.separator + "build" + File.separator,
        File.separator + "target" + File.separator,
        File.separator + "out" + File.separator,
        File.separator + "gradle" + File.separator + "wrapper" + File.separator,
        File.separator + "generated-sources" + File.separator,
        File.separator + "generated" + File.separator
    );

    // Regex to find the START of a top-level message or enum definition
    private static final Pattern PROTO_DEFINITION_START_PATTERN = Pattern.compile(
        "^\\s*(message|enum)\\s+([\\w_]+)\\s*\\{.*$"); // Matches "message Name {" or "enum Name {"

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java JavaCodeParser <path_to_project>");
            System.exit(1);
        }
        String projectPath = args[0];
        Path root = Paths.get(projectPath);

        StaticJavaParser.setConfiguration(parserConfiguration);

        try (PrintWriter errorWriter = new PrintWriter(new FileWriter(ERROR_LOG_FILE, StandardCharsets.UTF_8), true)) {
            List<Object> allParsedObjects = processProject(root, errorWriter);
            if (allParsedObjects != null) {
                writeOutput(allParsedObjects, errorWriter);
            }
        } catch (IOException e) {
            handleCriticalError("Could not open error log file " + ERROR_LOG_FILE, e);
        } catch (Exception e) {
            handleCriticalError("An unexpected error occurred", e);
        }
    }

    /**
     * Processes the entire project, finding files, parsing them (Java or Proto), and collecting results.
     */
    private static List<Object> processProject(Path root, PrintWriter errorWriter) {
        System.out.println("Starting project processing...");
        try (Stream<Path> paths = Files.walk(root)) {
            List<Object> results = paths
                .filter(Files::isRegularFile)
                .filter(JavaCodeParser::isIncludedPath)
                .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".proto"))
                .map(path -> parseFileDispatcher(path, root, errorWriter))
                .flatMap(List::stream)
                .collect(Collectors.toList());
            System.out.println("Finished project processing.");
            return results;
        } catch (IOException e) {
            errorWriter.println("FATAL: Failed during file traversal: " + e.getMessage());
            e.printStackTrace(errorWriter);
            System.err.println("Error during file traversal. See " + ERROR_LOG_FILE);
            return null;
        }
    }

    /**
     * Checks if a path should be included in the parsing based on exclusion rules.
     */
    private static boolean isIncludedPath(Path path) {
        String pathString = path.toString();
        return EXCLUDED_PATHS.stream().noneMatch(pathString::contains);
    }


    /**
     * Dispatches file parsing based on the file extension.
     */
    private static List<Object> parseFileDispatcher(Path filePath, Path rootPath, PrintWriter errorWriter) {
        String fileName = filePath.toString();
        if (fileName.endsWith(".java")) {
            return parseJavaFile(filePath, rootPath, errorWriter);
        } else if (fileName.endsWith(".proto")) {
            return parseProtoFile(filePath, rootPath, errorWriter);
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Parses a single Java file using JavaParser.
     */
    private static List<Object> parseJavaFile(Path filePath, Path rootPath, PrintWriter errorWriter) {
        List<Object> parsedObjects = new ArrayList<>();
        String relativePath = "";
        try {
            relativePath = rootPath.relativize(filePath).toString();
            CompilationUnit cu = StaticJavaParser.parse(filePath);

            MethodVisitor methodVisitor = new MethodVisitor(relativePath);
            methodVisitor.visit(cu, null);
            parsedObjects.addAll(methodVisitor.getMethods());

            ClassVisitor classVisitor = new ClassVisitor(relativePath);
            classVisitor.visit(cu, null);
            parsedObjects.addAll(classVisitor.getClasses());

            return parsedObjects;

        } catch (ParseProblemException e) {
            logParsingError(errorWriter, filePath, "Failed to parse Java file");
            e.getProblems().forEach(problem -> errorWriter.println("  - " + problem.getVerboseMessage()));
        } catch (Exception e) {
            logParsingError(errorWriter, filePath, "Failed to process Java file: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Parses a single .proto file using line-by-line reading and brace tracking
     * for top-level message/enum definitions. No external libraries needed.
     */
    private static List<Object> parseProtoFile(Path filePath, Path rootPath, PrintWriter errorWriter) {
        List<Object> parsedObjects = new ArrayList<>();
        String relativePath = "";
        int currentLineNumber = 0;
        int braceLevel = 0;
        StringBuilder currentSnippet = null;
        ParsedClass currentProtoDef = null;

        try {
            relativePath = rootPath.relativize(filePath).toString();
            // Use BufferedReader for efficient line-by-line reading
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    currentLineNumber++;

                    // If we are not inside a definition, look for the start of one
                    if (braceLevel == 0) {
                        Matcher matcher = PROTO_DEFINITION_START_PATTERN.matcher(line);
                        if (matcher.find()) {
                            // Found the start of a top-level message or enum
                            braceLevel = 1; // Initial opening brace
                            String defType = matcher.group(1); // "message" or "enum"
                            String defName = matcher.group(2);

                            currentProtoDef = new ParsedClass();
                            currentProtoDef.name = defName;
                            currentProtoDef.type = defType;
                            currentProtoDef.filePath = relativePath;
                            currentProtoDef.language = "proto";
                            currentProtoDef.startLine = currentLineNumber;

                            currentSnippet = new StringBuilder();
                            currentSnippet.append(line).append("\n");

                            // // Adjust brace level for any additional braces on the start line
                            braceLevel -= countChar(line, '}');

                            // If the definition ends on the same line (unlikely but possible)
                            if (braceLevel == 0 && currentProtoDef != null) {
                                currentProtoDef.endLine = currentLineNumber;
                                currentProtoDef.codeSnippet = currentSnippet.toString();
                                parsedObjects.add(currentProtoDef);
                                currentProtoDef = null; // Reset
                                currentSnippet = null;
                            }
                        }
                    } else { // We are inside a definition, track braces
                        currentSnippet.append(line).append("\n");
                        braceLevel += countChar(line, '{');
                        braceLevel -= countChar(line, '}');

                        if (braceLevel == 0) { // Found the matching closing brace
                            currentProtoDef.endLine = currentLineNumber;
                            currentProtoDef.codeSnippet = currentSnippet.toString();
                            parsedObjects.add(currentProtoDef);
                            currentProtoDef = null; // Reset for next top-level definition
                            currentSnippet = null;
                        } else if (braceLevel < 0) {
                            // This indicates mismatched braces, log error and reset
                            logParsingError(errorWriter, filePath, "Mismatched braces detected near line " + currentLineNumber);
                            braceLevel = 0; // Reset
                            currentProtoDef = null;
                            currentSnippet = null;
                        }
                    }
                }
            }
            // Check if we reached EOF while still inside a definition (mismatched braces)
            if (braceLevel > 0 && currentProtoDef != null) {
                logParsingError(errorWriter, filePath, "Reached end of file with unclosed definition: " + currentProtoDef.name + " starting at line " + currentProtoDef.startLine);
            }

            return parsedObjects;

        } catch (IOException e) {
            logParsingError(errorWriter, filePath, "Failed to read proto file: " + e.getMessage());
        } catch (Exception e) {
            logParsingError(errorWriter, filePath, "Failed to process proto file: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Helper method to count occurrences of a character in a string.
     * Note: This simple count doesn't account for characters within comments or strings.
     */
    private static int countChar(String str, char c) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }


    /**
     * Writes the collected parsed objects to the output JSON file.
     */
    private static void writeOutput(List<Object> results, PrintWriter errorWriter) {
        System.out.println("Writing results to " + OUTPUT_JSON_FILE + "...");
        try {
            objectMapper.writeValue(new File(OUTPUT_JSON_FILE), results);
            System.out.println("Successfully processed project.");
            System.out.println("Results written to: " + OUTPUT_JSON_FILE);
            System.out.println("Errors (if any) logged to: " + ERROR_LOG_FILE);
        } catch (IOException e) {
            errorWriter.println("FATAL: Failed to write JSON output to " + OUTPUT_JSON_FILE + ": " + e.getMessage());
            e.printStackTrace(errorWriter);
            System.err.println("Failed to write JSON output. See " + ERROR_LOG_FILE);
        }
    }

    /**
     * Logs a parsing error message consistently.
     */
    private static void logParsingError(PrintWriter writer, Path filePath, String message) {
        writer.println("ERROR: " + message + ": " + filePath);
    }

    /**
     * Handles critical errors (e.g., file system issues) by printing to stderr and the error log.
     */
    private static void handleCriticalError(String message, Exception cause) {
        System.err.println("CRITICAL: " + message + ": " + cause.getMessage());
        cause.printStackTrace();
        try (PrintWriter errorWriter = new PrintWriter(new FileWriter(ERROR_LOG_FILE, StandardCharsets.UTF_8, true))) {
            errorWriter.println("CRITICAL: " + message + ": " + cause.getMessage());
            cause.printStackTrace(errorWriter);
        } catch (IOException logEx) {
            System.err.println("Additionally failed to write critical error to log file: " + logEx.getMessage());
        }
    }


    // --- Visitor classes (Only used for Java) ---
    // (Visitor code remains unchanged from the previous version)
    private static class MethodVisitor extends VoidVisitorAdapter<Void> {
        private final List<ParsedMethod> methods = new ArrayList<>();
        private final String filePath;

        public MethodVisitor(String filePath) { this.filePath = filePath; }

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            super.visit(md, arg);
            ParsedMethod pm = new ParsedMethod();
            pm.name = md.getNameAsString();
            pm.signature = md.getDeclarationAsString(false, false, false);
            pm.filePath = this.filePath;
            pm.language = "java"; // Ensure language is set
            md.getRange().ifPresent(range -> {
                pm.startLine = range.begin.line;
                pm.endLine = range.end.line;
            });
            pm.codeSnippet = md.toString();
            methods.add(pm);
        }
        public List<ParsedMethod> getMethods() { return methods; }
    }


    // Visitor for classes, interfaces, and records
    private static class ClassVisitor extends VoidVisitorAdapter<Void> {
        private final List<ParsedClass> classes = new ArrayList<>();
        private final String filePath;

        public ClassVisitor(String filePath) { this.filePath = filePath; }

        @Override
        public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
            super.visit(cid, arg);
            // Pass the Optional<Range> directly to the helper
            ParsedClass pc = createParsedClass(cid.getNameAsString(),
                cid.isInterface() ? "interface" : "class",
                cid.getRange()); // Pass Optional<Range>
            pc.codeSnippet = cid.toString();
            classes.add(pc);
        }

        @Override
        public void visit(RecordDeclaration rd, Void arg) {
            super.visit(rd, arg);
            // Pass the Optional<Range> directly to the helper
            ParsedClass pc = createParsedClass(rd.getNameAsString(),
                "record",
                rd.getRange()); // Pass Optional<Range>
            pc.codeSnippet = rd.toString();
            classes.add(pc);
        }

        // Helper accepts Optional<Range> now
        private ParsedClass createParsedClass(String name, String type, java.util.Optional<com.github.javaparser.Range> optionalRange) {
            ParsedClass pc = new ParsedClass();
            pc.name = name;
            pc.type = type;
            pc.filePath = this.filePath;
            pc.language = "java"; // Set language for Java classes/records

            // Use ifPresent on the Optional parameter here
            optionalRange.ifPresent(r -> {
                pc.startLine = r.begin.line;
                pc.endLine = r.end.line;
            });
            // If range is not present, startLine and endLine will remain 0 (default for int)

            return pc;
        }

        public List<ParsedClass> getClasses() { return classes; }
    }
}