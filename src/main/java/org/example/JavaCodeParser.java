package org.example;

import java.io.File; // For File object
import java.io.FileWriter; // For writing to files
import java.io.IOException; // For IO exceptions
import java.io.PrintWriter; // For formatted file writing
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections; // For empty list if parsing fails
import java.util.List;
import java.util.stream.Collectors; // For stream collection
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature; // For pretty printing JSON
import com.github.javaparser.ParseProblemException; // More specific exception
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.pojo.ParsedClass;
import org.example.pojo.ParsedMethod;

public class JavaCodeParser {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT); // Enable pretty printing JSON

    private static final ParserConfiguration parserConfiguration = new ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21); // Or adjust as needed

    // --- File Names ---
    private static final String OUTPUT_JSON_FILE = "output.json";
    private static final String ERROR_LOG_FILE = "errors.log";

    public static void main(String[] args) throws Exception { // Keep throws for initial setup errors
        if (args.length == 0) {
            System.err.println("Usage: java JavaCodeParser <path_to_java_project>");
            System.exit(1);
        }
        String projectPath = args[0];
        Path root = Paths.get(projectPath);

        // Set the configuration for StaticJavaParser BEFORE parsing
        StaticJavaParser.setConfiguration(parserConfiguration);

        // Use try-with-resources for the error log writer
        try (PrintWriter errorWriter = new PrintWriter(new FileWriter(ERROR_LOG_FILE), true)) { // autoFlush=true

            List<Object> allParsedObjects;

            // 1. File Traversal and Parsing (Collect results)
            try (Stream<Path> paths = Files.walk(root)) {
                allParsedObjects = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    // Map each path to a list of parsed objects (methods/classes)
                    .map(path -> parseFile(path, root, errorWriter))
                    // Flatten the stream of lists into a single stream of objects
                    .flatMap(List::stream)
                    // Collect all objects into a single list
                    .collect(Collectors.toList());

            } catch (IOException e) {
                errorWriter.println("FATAL: Failed during file traversal: " + e.getMessage());
                e.printStackTrace(errorWriter); // Log stack trace to error file
                System.err.println("Error during file traversal. See " + ERROR_LOG_FILE);
                return; // Exit if traversal fails
            }

            // 2. Write collected results to JSON file
            try {
                objectMapper.writeValue(new File(OUTPUT_JSON_FILE), allParsedObjects);
                System.out.println("Successfully parsed project.");
                System.out.println("Results written to: " + OUTPUT_JSON_FILE);
                System.out.println("Errors (if any) logged to: " + ERROR_LOG_FILE);
            } catch (IOException e) {
                // Log this error to the error file as well
                errorWriter.println("FATAL: Failed to write JSON output to " + OUTPUT_JSON_FILE + ": " + e.getMessage());
                e.printStackTrace(errorWriter);
                System.err.println("Failed to write JSON output. See " + ERROR_LOG_FILE);
            }

        } catch (IOException e) {
            // Catch errors opening the error log file itself
            System.err.println("CRITICAL: Could not open error log file " + ERROR_LOG_FILE + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Parses a single Java file and returns a list of parsed objects (ParsedMethod, ParsedClass).
     * Errors during parsing are written to the provided errorWriter.
     *
     * @param filePath    Path to the Java file.
     * @param rootPath    Root path of the project for relativizing file paths.
     * @param errorWriter Writer for logging parsing errors.
     * @return List containing ParsedMethod and ParsedClass objects found in the file, or empty list on error.
     */
    private static List<Object> parseFile(Path filePath, Path rootPath, PrintWriter errorWriter) {
        List<Object> parsedObjects = new ArrayList<>();
        try {
            String relativePath = rootPath.relativize(filePath).toString();
            // Use the configured parser (implicitly uses the config set in main)
            CompilationUnit cu = StaticJavaParser.parse(filePath);

            // Use Visitors to extract data
            MethodVisitor methodVisitor = new MethodVisitor(relativePath);
            methodVisitor.visit(cu, null);
            parsedObjects.addAll(methodVisitor.getMethods()); // Add methods to the list

            ClassVisitor classVisitor = new ClassVisitor(relativePath);
            classVisitor.visit(cu, null);
            parsedObjects.addAll(classVisitor.getClasses()); // Add classes/records to the list

            return parsedObjects; // Return the list of objects found

        } catch (ParseProblemException e) {
            // Handle errors specifically from JavaParser
            errorWriter.println("ERROR: Failed to parse file: " + filePath);
            // Log detailed problems
            e.getProblems().forEach(problem -> errorWriter.println("  - " + problem.getVerboseMessage()));
        } catch (Exception e) {
            // Catch other potential exceptions during parsing
            errorWriter.println("ERROR: Failed to process file: " + filePath + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // Optionally log stack trace for unexpected errors: e.printStackTrace(errorWriter);
        }
        // Return an empty list if parsing failed for this file
        return Collections.emptyList();
    }

    // --- Visitor classes remain the same as the previous version ---

    // Visitor specifically for methods
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
            ParsedClass pc = new ParsedClass();
            pc.name = cid.getNameAsString();
            pc.type = cid.isInterface() ? "interface" : "class";
            pc.filePath = this.filePath;
            cid.getRange().ifPresent(range -> {
                pc.startLine = range.begin.line;
                pc.endLine = range.end.line;
            });
            pc.codeSnippet = cid.toString();
            classes.add(pc);
        }

        @Override
        public void visit(RecordDeclaration rd, Void arg) {
            super.visit(rd, arg);
            ParsedClass pc = new ParsedClass();
            pc.name = rd.getNameAsString();
            pc.type = "record";
            pc.filePath = this.filePath;
            rd.getRange().ifPresent(range -> {
                pc.startLine = range.begin.line;
                pc.endLine = range.end.line;
            });
            pc.codeSnippet = rd.toString();
            classes.add(pc);
        }
        public List<ParsedClass> getClasses() { return classes; }
    }
}