// src/main/java/org/example/JavaCodeParser.java

package org.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.example.pojo.ParsedCodeElement;


public class JavaCodeParser {

    // --- Configuration ---
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    // Optional: Configure to skip null fields in JSON output
    // .setSerializationInclusion(JsonInclude.Include.NON_NULL); // Or use annotation in POJO

    private static final ParserConfiguration parserConfiguration = new ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private static final String OUTPUT_JSON_FILE = "output.json";
    private static final String ERROR_LOG_FILE = "errors.log";

    private static final Set<String> EXCLUDED_PATHS = Set.of(
        // Add OS-agnostic path separators if needed, but File.separator works
        File.separator + "build" + File.separator,
        File.separator + "target" + File.separator,
        File.separator + "out" + File.separator,
        File.separator + "gradle" + File.separator + "wrapper" + File.separator,
        File.separator + "generated-sources" + File.separator,
        File.separator + "generated" + File.separator,
        File.separator + ".git" + File.separator,
        File.separator + ".idea" + File.separator,
        File.separator + ".vscode" + File.separator
    );

    // Regex to find the START of a top-level message or enum definition
    // Captures potential preceding single-line comments for docstring
    private static final Pattern PROTO_DEFINITION_START_PATTERN = Pattern.compile(
        "^(\\s*//.*\\n)*?" + // Optional single-line comments preceding
        "\\s*(message|enum)\\s+([\\w_]+)\\s*\\{.*$"); // Matches "message Name {" or "enum Name {"

    // Regex to find proto package declaration
    private static final Pattern PROTO_PACKAGE_PATTERN = Pattern.compile(
        "^\\s*package\\s+([\\w.]+);");

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java -jar your-parser.jar <path_to_project>");
            System.exit(1);
        }
        String projectPath = args[0];
        Path root = Paths.get(projectPath).toAbsolutePath(); // Use absolute path for consistency

        StaticJavaParser.setConfiguration(parserConfiguration);

        try (PrintWriter errorWriter = new PrintWriter(new FileWriter(ERROR_LOG_FILE, StandardCharsets.UTF_8), true)) {
            List<ParsedCodeElement> allParsedElements = processProject(root, errorWriter); // Changed type
            if (allParsedElements != null) {
                writeOutput(allParsedElements, errorWriter); // Changed type
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
    private static List<ParsedCodeElement> processProject(Path root, PrintWriter errorWriter) {
        System.out.println("Starting project processing in: " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            List<ParsedCodeElement> results = paths
                .filter(Files::isRegularFile)
                .filter(path -> isIncludedPath(path, root)) // Pass root for better exclusion logic
                .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".proto"))
                .peek(path -> System.out.println("Processing: " + root.relativize(path))) // Log processed file
                .map(path -> parseFileDispatcher(path, root, errorWriter))
                .flatMap(List::stream) // Flatten the lists of elements from each file
                .collect(Collectors.toList());
            System.out.println("Finished project processing. Found " + results.size() + " code elements.");
            return results;
        } catch (IOException e) {
            errorWriter.println("FATAL: Failed during file traversal: " + e.getMessage());
            e.printStackTrace(errorWriter);
            System.err.println("Error during file traversal. See " + ERROR_LOG_FILE);
            return null;
        } catch (Exception e) { // Catch potential errors during stream processing
            errorWriter.println("FATAL: Error during file processing stream: " + e.getMessage());
            e.printStackTrace(errorWriter);
            System.err.println("Error during file processing. See " + ERROR_LOG_FILE);
            return null;
        }
    }

    /**
     * Checks if a path should be included based on exclusion rules relative to the root.
     */
    private static boolean isIncludedPath(Path path, Path root) {
        String relativePathString = File.separator + root.relativize(path).toString();
        // Check if the relative path starts with any excluded directory pattern
        return EXCLUDED_PATHS.stream().noneMatch(excluded -> relativePathString.startsWith(excluded) || relativePathString.contains(excluded));
    }


    /**
     * Dispatches file parsing based on the file extension.
     */
    private static List<ParsedCodeElement> parseFileDispatcher(Path filePath, Path rootPath, PrintWriter errorWriter) {
        String fileName = filePath.toString();
        if (fileName.endsWith(".java")) {
            return parseJavaFile(filePath, rootPath, errorWriter);
        } else if (fileName.endsWith(".proto")) {
            return parseProtoFile(filePath, rootPath, errorWriter);
        } else {
            return Collections.emptyList(); // Should not happen due to filter
        }
    }

    /**
     * Parses a single Java file using JavaParser, extracting only methods.
     */
    private static List<ParsedCodeElement> parseJavaFile(Path filePath, Path rootPath, PrintWriter errorWriter) {
        String relativePath = "";
        String fileName = filePath.getFileName().toString();
        try {
            relativePath = rootPath.relativize(filePath).toString().replace('\\', '/'); // Normalize path sep
            CompilationUnit cu = StaticJavaParser.parse(filePath);

            // Extract package name once
            String packageName = cu.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse(null); // Use null if no package declaration

            // Use the new MethodVisitor
            MethodVisitor methodVisitor = new MethodVisitor(relativePath, fileName, packageName);
            methodVisitor.visit(cu, null);
            return methodVisitor.getMethods(); // Return the list directly

        } catch (ParseProblemException e) {
            logParsingError(errorWriter, filePath, "Failed to parse Java file");
            e.getProblems().forEach(problem -> {
                // Correct way to get the line number from the TokenRange
                String lineNumber = problem.getLocation() // Optional<TokenRange>
                    .flatMap(TokenRange::toRange) // Optional<Range> - Use flatMap as toRange returns Optional
                    .map(range -> String.valueOf(range.begin.line)) // Optional<String> - Map Range to line number string
                    .orElse("?"); // Provide "?" if location or range is missing

                errorWriter.println(
                    "  - Line " + lineNumber + ": " + problem.getVerboseMessage());
            });
        }
        // ... rest of the catch blocks
        catch (StackOverflowError e) {
            logParsingError(errorWriter, filePath,
                "StackOverflowError during Java parsing (likely complex file or bug in JavaParser). Skipping file.");
        } catch (Exception e) {
            logParsingError(errorWriter, filePath, "Failed to process Java file: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(errorWriter); // Log stack trace for unexpected errors
        }
        return Collections.emptyList(); // Return empty list on error
    }

    /**
     * Parses a single .proto file using line-by-line reading and brace tracking
     * for top-level message/enum definitions.
     */
    private static List<ParsedCodeElement> parseProtoFile(Path filePath, Path rootPath, PrintWriter errorWriter) {
        List<ParsedCodeElement> parsedElements = new ArrayList<>();
        String relativePath = "";
        String fileName = filePath.getFileName().toString();
        int currentLineNumber = 0;
        int braceLevel = 0;
        StringBuilder currentSnippet = null;
        ParsedCodeElement currentProtoDef = null;
        String protoPackage = null;
        List<String> precedingComments = new ArrayList<>(); // To capture potential doc comments

        try {
            relativePath = rootPath.relativize(filePath).toString().replace('\\', '/'); // Normalize path sep

            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            for (String line : lines) {
                currentLineNumber++;
                String trimmedLine = line.trim();

                // Look for package declaration (only outside definitions)
                if (braceLevel == 0 && protoPackage == null) {
                    Matcher packageMatcher = PROTO_PACKAGE_PATTERN.matcher(trimmedLine);
                    if (packageMatcher.find()) {
                        protoPackage = packageMatcher.group(1);
                    }
                }

                // Track potential doc comments immediately preceding a definition
                if (braceLevel == 0 && trimmedLine.startsWith("//")) {
                    precedingComments.add(trimmedLine.substring(2).trim()); // Store comment content
                } else if (braceLevel == 0 && !trimmedLine.isEmpty() && !trimmedLine.startsWith("syntax ") && !trimmedLine.startsWith("package ")
                           && !trimmedLine.startsWith("import ") && !trimmedLine.startsWith("option ")) {
                    // If we encounter a non-empty, non-comment, non-directive line and are not in a definition,
                    // check if it's the start of a message/enum. If not, clear preceding comments.
                    Matcher startMatcher = PROTO_DEFINITION_START_PATTERN.matcher(line); // Match on original line for line number accuracy
                    if (!startMatcher.find()) {
                        precedingComments.clear(); // Clear comments if they didn't lead into a definition
                    }
                }


                // If we are not inside a definition, look for the start of one
                if (braceLevel == 0) {
                    // Match against the original line, not trimmed, to preserve indentation for snippet
                    Matcher matcher = PROTO_DEFINITION_START_PATTERN.matcher(line);
                    if (matcher.find()) {
                        // Found the start of a top-level message or enum
                        braceLevel = 1; // Initial opening brace from pattern match (assuming it includes '{')
                        // braceLevel += countChar(line, '{'); // Count all '{' on the line
                        braceLevel -= countChar(line, '}'); // Count all '}' on the line


                        String defType = matcher.group(2); // "message" or "enum"
                        String defName = matcher.group(3);

                        currentProtoDef = new ParsedCodeElement();
                        currentProtoDef.name = defName;
                        currentProtoDef.codeType = defType.substring(0, 1).toUpperCase() + defType.substring(1); // Capitalize: Message, Enum
                        currentProtoDef.signature = null; // Not applicable for proto message/enum
                        currentProtoDef.line = currentLineNumber; // Line where the definition starts
                        currentProtoDef.lineFrom = currentLineNumber - precedingComments.size(); // Start line including comments
                        // Combine preceding comments into a docstring
                        currentProtoDef.docstring = precedingComments.isEmpty() ? null : String.join("\n", precedingComments);

                        currentProtoDef.context = new ParsedCodeElement.Context();
                        currentProtoDef.context.filePath = relativePath;
                        currentProtoDef.context.fileName = fileName;
                        currentProtoDef.context.module = protoPackage; // Use found package
                        currentProtoDef.context.className = null; // Not applicable

                        currentSnippet = new StringBuilder();
                        // Add comments to snippet if they were captured
                        for (String comment : precedingComments) {
                            currentSnippet.append("// ").append(comment).append("\n");
                        }
                        currentSnippet.append(line).append("\n");

                        precedingComments.clear(); // Clear comments after use

                        // If the definition ends on the same line
                        if (braceLevel == 0 && currentProtoDef != null) {
                            currentProtoDef.lineTo = currentLineNumber;
                            currentProtoDef.context.snippet = currentSnippet.toString();
                            parsedElements.add(currentProtoDef);
                            currentProtoDef = null; // Reset
                            currentSnippet = null;
                        } else if (braceLevel < 0) {
                            // Should ideally not happen with the improved counting, but as a safeguard:
                            logParsingError(errorWriter, filePath,
                                "Proto Parse Error: Brace level negative on definition start line " + currentLineNumber + ". Definition: " + defName);
                            braceLevel = 0; // Attempt to recover
                            currentProtoDef = null;
                            currentSnippet = null;
                        }
                    }
                    // else { // If not starting a definition, clear comments if outside definition
                    //     precedingComments.clear(); moved logic above
                    // }
                } else { // We are inside a definition, track braces and collect snippet
                    if (currentSnippet != null) { // Ensure we have an active snippet
                        currentSnippet.append(line).append("\n");
                        braceLevel += countChar(line, '{');
                        braceLevel -= countChar(line, '}');

                        if (braceLevel == 0) { // Found the matching closing brace
                            currentProtoDef.lineTo = currentLineNumber;
                            currentProtoDef.context.snippet = currentSnippet.toString();
                            parsedElements.add(currentProtoDef);
                            currentProtoDef = null; // Reset for next top-level definition
                            currentSnippet = null;
                            precedingComments.clear(); // Clear comments after a definition ends
                        } else if (braceLevel < 0) {
                            // This indicates mismatched braces, log error and reset
                            logParsingError(errorWriter, filePath,
                                "Proto Parse Error: Mismatched braces detected near line " + currentLineNumber + ". Resetting state.");
                            braceLevel = 0; // Reset
                            currentProtoDef = null;
                            currentSnippet = null;
                            precedingComments.clear();
                        }
                    } else {
                        // This case means braceLevel > 0 but currentSnippet is null.
                        // Indicates an error state, likely from a previous mismatch recovery.
                        // We just continue, hoping to find a new definition start.
                        if (!trimmedLine.isEmpty()) { // Avoid logging for empty lines in error state
                            // logParsingError(errorWriter, filePath, "Proto Parse Info: Skipping line " + currentLineNumber + " while in error
                            // recovery state (braceLevel=" + braceLevel + ")");
                        }
                        // Reset brace level if we encounter a potential start outside the error block? Maybe too complex.
                        // Safest is to just ignore lines until braceLevel resets or a new definition starts.
                    }
                }
                // If not inside a definition and line wasn't a comment, clear preceding comments
                if (braceLevel == 0 && !trimmedLine.startsWith("//") && !PROTO_DEFINITION_START_PATTERN.matcher(line).find()) {
                    precedingComments.clear();
                }
            } // End of line loop

            // Check if we reached EOF while still inside a definition (mismatched braces)
            if (braceLevel > 0 && currentProtoDef != null) {
                logParsingError(errorWriter, filePath,
                    "Proto Parse Error: Reached end of file with unclosed definition: " + currentProtoDef.name + " starting at line "
                    + currentProtoDef.lineFrom);
                // Optionally add the incomplete element with estimated end line?
                // currentProtoDef.lineTo = currentLineNumber; // Last line number
                // currentProtoDef.context.snippet = currentSnippet.toString();
                // parsedElements.add(currentProtoDef); // Add potentially incomplete element
            }

            return parsedElements;

        } catch (IOException e) {
            logParsingError(errorWriter, filePath, "Failed to read proto file: " + e.getMessage());
        } catch (Exception e) {
            logParsingError(errorWriter, filePath, "Failed to process proto file: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(errorWriter);
        }
        return Collections.emptyList();
    }

    /**
     * Helper method to count occurrences of a character in a string,
     * ignoring comments and strings. Basic implementation for braces.
     */
    private static int countChar(String str, char c) {
        int count = 0;
        boolean inSingleComment = false;
        boolean inMultiComment = false;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < str.length(); i++) {
            char current = str.charAt(i);
            char prev = (i > 0) ? str.charAt(i - 1) : 0;

            // Check for comment start/end
            if (!inString) {
                if (current == '/' && prev == '/') {
                    inSingleComment = true;
                } else if (current == '*' && prev == '/') {
                    inMultiComment = true;
                } else if (current == '/' && prev == '*' && inMultiComment) {
                    inMultiComment = false;
                    continue; // Skip the '/' of '*/'
                }
            }

            // Check for string start/end (basic, doesn't handle escapes perfectly)
            if (!inSingleComment && !inMultiComment) {
                if ((current == '"' || current == '\'') && prev != '\\') { // Handle basic escapes
                    if (inString && current == stringChar) {
                        inString = false;
                    } else if (!inString) {
                        inString = true;
                        stringChar = current;
                    }
                }
            }


            // Count the character if not in comments or strings
            if (!inSingleComment && !inMultiComment && !inString && current == c) {
                count++;
            }

            // Reset single line comment at end of (conceptual) line - handled by processing line by line

        }
        // Single line comment ends with the line processing, multi-comment/string state persists if unterminated on this line.
        // For brace counting, this simplified approach is often sufficient for basic proto structure.
        return count;
    }


    /**
     * Writes the collected parsed code elements to the output JSON file.
     */
    private static void writeOutput(List<ParsedCodeElement> results, PrintWriter errorWriter) { // Updated type
        System.out.println("Writing " + results.size() + " results to " + OUTPUT_JSON_FILE + "...");
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
        System.err.println("WARN: " + message + ": " + filePath); // Also print to stderr for visibility
    }

    /**
     * Handles critical errors (e.g., file system issues) by printing to stderr and the error log.
     */
    private static void handleCriticalError(String message, Exception cause) {
        System.err.println("CRITICAL: " + message + ": " + cause.getMessage());
        cause.printStackTrace(System.err); // Print stack trace to stderr immediately
        try (PrintWriter errorWriter = new PrintWriter(new FileWriter(ERROR_LOG_FILE, StandardCharsets.UTF_8, true))) {
            errorWriter.println("CRITICAL: " + message + ": " + cause.getMessage());
            cause.printStackTrace(errorWriter);
        } catch (IOException logEx) {
            System.err.println("Additionally failed to write critical error to log file: " + logEx.getMessage());
        }
    }


    // --- Visitor class for Java Methods ---
    // No ClassVisitor needed anymore
    private static class MethodVisitor extends VoidVisitorAdapter<Void> {
        private final List<ParsedCodeElement> methods = new ArrayList<>();
        private final String relativeFilePath;
        private final String fileName;
        private final String packageName; // Pass package name in

        public MethodVisitor(String relativeFilePath, String fileName, String packageName) {
            this.relativeFilePath = relativeFilePath;
            this.fileName = fileName;
            this.packageName = packageName; // Store package name
        }

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            super.visit(md, arg);

            ParsedCodeElement element = new ParsedCodeElement();
            element.name = md.getNameAsString();
            // Get signature without modifiers or annotations initially
            element.signature = md.getDeclarationAsString(false, false, false);
            element.codeType = "Method"; // Set code type

            // Attempt to find the enclosing class/interface/record name
            // Declare ancestor to match the return type of findAncestor
            Optional<TypeDeclaration> ancestor = md.findAncestor(TypeDeclaration.class);

            // The rest of the code works fine with Optional<TypeDeclaration>
            String className = ancestor.map(TypeDeclaration::getNameAsString).orElse(null);
            // Extract Javadoc
            element.docstring = md.getJavadocComment()
                .map(Comment::getContent)
                // Basic cleanup: remove leading *, trailing space/newline, leading space
                .map(s -> s.replaceAll("(?m)^\\s*\\* ?", "").trim())
                .orElse(null);

            element.context = new ParsedCodeElement.Context();
            element.context.filePath = this.relativeFilePath;
            element.context.fileName = this.fileName;
            element.context.module = this.packageName; // Use stored package name
            element.context.className = className; // Assign found class name
            element.context.snippet = md.toString(); // Full method code as snippet

            // Get line numbers
            md.getRange().ifPresent(range -> {
                element.line = range.begin.line; // Line where method declaration starts
                element.lineTo = range.end.line; // End line of the method block

                // Estimate lineFrom to include Javadoc if present
                int startLine = range.begin.line;
                if (md.getComment().isPresent()) {
                    // If comment exists, try to get its start line
                    startLine = md.getComment().get().getRange()
                        .map(commentRange -> commentRange.begin.line)
                        .orElse(startLine); // Default to method start if comment range fails
                }
                element.lineFrom = startLine;
            });

            // Fallback if range is somehow not present (shouldn't happen for methods)
            if (element.lineFrom == 0) {
                element.line = -1; // Indicate unknown line
                element.lineFrom = -1;
                element.lineTo = -1;
            }

            methods.add(element);
        }

        public List<ParsedCodeElement> getMethods() {
            return methods;
        }
    }

    // Removed ClassVisitor entirely
}