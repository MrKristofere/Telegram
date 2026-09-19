import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class FirebasePatcher {

    private static final String FIREBASE_CLASS =
            "Lcom/google/firebase/installations/remote/FirebaseInstallationServiceClient;";

    private static final String FINGERPRINT_METHOD =
            "getFingerprintHashForPackage";

    private static final String CONNECTION_METHOD =
            "openHttpUrlConnection";

    private static final String ADD_REQUEST_PROPERTY =
            "addRequestProperty";

    public static void main(String[] args) throws Exception {

        if (args.length != 3) {
            System.err.println(
                    "Usage: java -jar firebase-apk-patcher.jar " +
                    "<input.apk> <output.apk> <sha1>"
            );
            System.exit(2);
        }

        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        String hash = normalizeSha1(args[2]);

        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException(
                    "Input APK does not exist: " + input
            );
        }

        System.out.println("Input APK : " + input);
        System.out.println("Output APK: " + output);
        System.out.println("Firebase SHA-1: " + hash);

        patchApk(input, output, hash);

        System.out.println();
        System.out.println("Firebase patch completed.");
        System.out.println("Output: " + output);
    }

    private static String normalizeSha1(String value) {

        String hash = value
                .replace(":", "")
                .replace(" ", "")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!hash.matches("[0-9A-F]{40}")) {
            throw new IllegalArgumentException(
                    "certificateHash must contain exactly 40 hexadecimal " +
                    "characters (SHA-1). Received: " + value
            );
        }

        return hash;
    }

    private static void patchApk(
            Path input,
            Path output,
            String hash
    ) throws Exception {

        Path tempDirectory =
                Files.createTempDirectory("firebase-patcher-");

        Map<String, byte[]> replacementDex = new HashMap<>();

        boolean fingerprintPatched = false;
        boolean headerPatched = false;

        try {

            MultiDexContainer<?> container =
                    DexFileFactory.loadDexContainer(
                            input.toFile(),
                            null
                    );

            List<String> dexEntries =
                    container.getDexEntryNames();

            System.out.println(
                    "DEX files found: " + dexEntries.size()
            );

            for (String dexEntryName : dexEntries) {

                System.out.println(
                        "Scanning " + dexEntryName
                );

                MultiDexContainer.DexEntry<?> entry =
                        container.getEntry(dexEntryName);

                if (entry == null) {
                    continue;
                }

                DexFile dexFile =
                        (DexFile) entry.getDexFile();

                PatchResult result =
                        patchDex(dexFile, hash);

                if (!result.changed) {
                    continue;
                }

                Path tempDex =
                        tempDirectory.resolve(
                                dexEntryName.replace("/", "_")
                        );

                DexFile modifiedDex =
                        new ImmutableDexFile(
                                dexFile.getOpcodes(),
                                result.classes
                        );

                DexPool.writeTo(
                        tempDex.toString(),
                        modifiedDex
                );

                replacementDex.put(
                        dexEntryName,
                        Files.readAllBytes(tempDex)
                );

                fingerprintPatched |=
                        result.fingerprintPatched;

                headerPatched |=
                        result.headerPatched;

                System.out.println(
                        "  modified: " + dexEntryName
                );
            }

            /*
             * Important:
             *
             * At least ONE patch must be found.
             *
             * If Firebase changes one of the two methods,
             * the other patch is still enough.
             */
            if (!fingerprintPatched && !headerPatched) {
                throw new IllegalStateException(
                        "Firebase targets were not found. " +
                        "The APK may use a different Firebase SDK " +
                        "version or obfuscation layout."
                );
            }

            System.out.println();
            System.out.println(
                    "getFingerprintHashForPackage(): " +
                    (fingerprintPatched ? "patched" : "not found")
            );

            System.out.println(
                    "X-Android-Cert request: " +
                    (headerPatched ? "patched" : "not found")
            );

            /*
             * Even if only one of the two patches was found,
             * rebuild the APK.
             */
            rebuildApk(
                    input,
                    output,
                    replacementDex
            );

        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private static PatchResult patchDex(
            DexFile dexFile,
            String hash
    ) {

        Set<ClassDef> modifiedClasses =
                new LinkedHashSet<>();

        boolean fingerprintPatched = false;
        boolean headerPatched = false;

        for (ClassDef classDef : dexFile.getClasses()) {

            boolean isFirebaseClass =
                    FIREBASE_CLASS.equals(classDef.getType());

            List<Method> directMethods =
                    toMethodList(classDef.getDirectMethods());

            List<Method> virtualMethods =
                    toMethodList(classDef.getVirtualMethods());

            boolean classChanged = false;

            /*
             * Direct methods.
             */
            for (int i = 0; i < directMethods.size(); i++) {

                Method method =
                        directMethods.get(i);

                PatchMethodResult result =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (result.method != method) {

                    directMethods.set(
                            i,
                            result.method
                    );

                    classChanged = true;

                    fingerprintPatched |=
                            result.fingerprintPatched;

                    headerPatched |=
                            result.headerPatched;
                }
            }

            /*
             * Virtual methods.
             */
            for (int i = 0; i < virtualMethods.size(); i++) {

                Method method =
                        virtualMethods.get(i);

                PatchMethodResult result =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (result.method != method) {

                    virtualMethods.set(
                            i,
                            result.method
                    );

                    classChanged = true;

                    fingerprintPatched |=
                            result.fingerprintPatched;

                    headerPatched |=
                            result.headerPatched;
                }
            }

            /*
             * Keep every class, including untouched classes.
             */
            if (classChanged) {

                modifiedClasses.add(
                        new ImmutableClassDef(
                                classDef.getType(),
                                classDef.getAccessFlags(),
                                classDef.getSuperclass(),
                                classDef.getInterfaces(),
                                classDef.getSourceFile(),
                                classDef.getAnnotations(),
                                classDef.getStaticFields(),
                                classDef.getInstanceFields(),
                                directMethods,
                                virtualMethods
                        )
                );

            } else {

                modifiedClasses.add(
                        ImmutableClassDef.of(classDef)
                );
            }
        }

        return new PatchResult(
                modifiedClasses,
                fingerprintPatched,
                headerPatched,
                fingerprintPatched || headerPatched
        );
    }

    private static PatchMethodResult patchMethod(
            Method method,
            boolean isFirebaseClass,
            String hash
    ) {

        MethodImplementation implementation =
                method.getImplementation();

        if (implementation == null) {
            return PatchMethodResult.unchanged(method);
        }

        /*
         * ============================================================
         * FIX 1
         *
         * FirebaseInstallationServiceClient
         * -> getFingerprintHashForPackage()
         *
         * Replace implementation with:
         *
         * const-string v0, "SHA1"
         * return-object v0
         * ============================================================
         */

        if (isFirebaseClass &&
                FINGERPRINT_METHOD.equals(method.getName()) &&
                "Ljava/lang/String;".equals(method.getReturnType())) {

            int registers =
                    Math.max(
                            implementation.getRegisterCount(),
                            1
                    );

            MutableMethodImplementation replacement =
                    new MutableMethodImplementation(
                            registers
                    );

            replacement.addInstruction(
                    new BuilderInstruction21c(
                            Opcode.CONST_STRING,
                            0,
                            new ImmutableStringReference(hash)
                    )
            );

            replacement.addInstruction(
                    new BuilderInstruction11x(
                            Opcode.RETURN_OBJECT,
                            0
                    )
            );

            Method patchedMethod =
                    new ImmutableMethod(
                            method.getDefiningClass(),
                            method.getName(),
                            method.getParameters(),
                            method.getReturnType(),
                            method.getAccessFlags(),
                            method.getAnnotations(),
                            method.getHiddenApiRestrictions(),
                            replacement
                    );

            return new PatchMethodResult(
                    patchedMethod,
                    true,
                    false
            );
        }

        /*
         * ============================================================
         * FIX 2
         *
         * We DO NOT search for the literal string:
         *
         *     "X-Android-Cert"
         *
         * because the APK can keep it in a static field:
         *
         *     X_ANDROID_CERT_HEADER_KEY
         *
         * Instead we inspect addRequestProperty().
         *
         * Expected source-level code:
         *
         * httpURLConnection.addRequestProperty(
         *     X_ANDROID_CERT_HEADER_KEY,
         *     getFingerprintHashForPackage()
         * );
         *
         * We find the invocation of:
         *
         *     addRequestProperty(String, String)
         *
         * and check whether its value register was populated by:
         *
         *     getFingerprintHashForPackage()
         *
         * If yes, replace that value with our SHA-1.
         * ============================================================
         */

        if (CONNECTION_METHOD.equals(method.getName())) {

            List<Instruction> instructions =
                    toInstructionList(
                            implementation.getInstructions()
                    );

            for (int i = 0; i < instructions.size(); i++) {

                Instruction instruction =
                        instructions.get(i);

                if (!(instruction instanceof ReferenceInstruction)) {
                    continue;
                }

                Object reference =
                        ((ReferenceInstruction) instruction)
                                .getReference();

                if (!(reference instanceof MethodReference)) {
                    continue;
                }

                MethodReference methodReference =
                        (MethodReference) reference;

                if (!ADD_REQUEST_PROPERTY.equals(
                        methodReference.getName())) {
                    continue;
                }

                /*
                 * addRequestProperty must have:
                 *
                 * (String, String)
                 *
                 * We only want the two-string overload.
                 */
                if (methodReference.getParameterTypes().size() != 2) {
                    continue;
                }

                if (!"Ljava/lang/String;".equals(
                        methodReference.getParameterTypes().get(0))) {
                    continue;
                }

                if (!"Ljava/lang/String;".equals(
                        methodReference.getParameterTypes().get(1))) {
                    continue;
                }

                int valueRegister =
                        getThirdArgumentRegister(instruction);

                if (valueRegister < 0) {
                    continue;
                }

                /*
                 * Check the instructions immediately before this
                 * invocation for:
                 *
                 * invoke-virtual {...}, getFingerprintHashForPackage()
                 *
                 * followed by:
                 *
                 * move-result-object <valueRegister>
                 *
                 * This matches the normal DEX representation of:
                 *
                 * addRequestProperty(...,
                 *     getFingerprintHashForPackage())
                 */
                int moveResultIndex =
                        findMoveResultForRegister(
                                instructions,
                                i,
                                valueRegister
                        );

                if (moveResultIndex < 0) {
                    continue;
                }

                int fingerprintInvokeIndex =
                        findFingerprintInvocation(
                                instructions,
                                moveResultIndex
                        );

                if (fingerprintInvokeIndex < 0) {
                    continue;
                }

                /*
                 * We have positively identified the
                 * X-Android-Cert value.
                 *
                 * Insert:
                 *
                 * const-string <valueRegister>, "<SHA1>"
                 *
                 * immediately before addRequestProperty().
                 */
                MutableMethodImplementation mutable =
                        new MutableMethodImplementation(
                                implementation
                        );

                mutable.addInstruction(
                        i,
                        new BuilderInstruction21c(
                                Opcode.CONST_STRING,
                                valueRegister,
                                new ImmutableStringReference(hash)
                        )
                );

                Method patchedMethod =
                        new ImmutableMethod(
                                method.getDefiningClass(),
                                method.getName(),
                                method.getParameters(),
                                method.getReturnType(),
                                method.getAccessFlags(),
                                method.getAnnotations(),
                                method.getHiddenApiRestrictions(),
                                mutable
                        );

                return new PatchMethodResult(
                        patchedMethod,
                        false,
                        true
                );
            }
        }

        return PatchMethodResult.unchanged(method);
    }

    /*
     * Returns the register containing the third argument of:
     *
     * addRequestProperty(connection, header, value)
     *
     * Supports:
     *
     * invoke-virtual {...}       -> FiveRegisterInstruction
     * invoke-virtual/range {...} -> RegisterRangeInstruction
     */
    private static int getThirdArgumentRegister(
            Instruction instruction
    ) {

        if (instruction instanceof FiveRegisterInstruction) {

            FiveRegisterInstruction five =
                    (FiveRegisterInstruction) instruction;

            /*
             * For a 3-register invoke:
             *
             * {vConnection, vHeader, vValue}
             *
             * vValue is register E.
             */
            return five.getRegisterE();
        }

        if (instruction instanceof RegisterRangeInstruction) {

            RegisterRangeInstruction range =
                    (RegisterRangeInstruction) instruction;

            if (range.getRegisterCount() < 3) {
                return -1;
            }

            return range.getStartRegister() + 2;
        }

        return -1;
    }

    /*
     * Find:
     *
     * move-result-object <targetRegister>
     *
     * directly before the addRequestProperty() call.
     *
     * We allow a small number of harmless instructions between
     * the result and the invocation.
     */
    private static int findMoveResultForRegister(
            List<Instruction> instructions,
            int requestPropertyIndex,
            int targetRegister
    ) {

        int start =
                Math.max(
                        0,
                        requestPropertyIndex - 4
                );

        for (int i = requestPropertyIndex - 1;
             i >= start;
             i--) {

            Instruction instruction =
                    instructions.get(i);

            if (instruction.getOpcode() ==
                    Opcode.MOVE_RESULT_OBJECT) {

                if (instruction instanceof org.jf.dexlib2.iface.instruction.OneRegisterInstruction) {

                    int register =
                            ((org.jf.dexlib2.iface.instruction.OneRegisterInstruction)
                                    instruction)
                                    .getRegisterA();

                    if (register == targetRegister) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /*
     * Find an invocation of:
     *
     * getFingerprintHashForPackage()
     *
     * immediately before move-result-object.
     */
    private static int findFingerprintInvocation(
            List<Instruction> instructions,
            int moveResultIndex
    ) {

        int start =
                Math.max(
                        0,
                        moveResultIndex - 3
                );

        for (int i = moveResultIndex - 1;
             i >= start;
             i--) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }

            Object reference =
                    ((ReferenceInstruction) instruction)
                            .getReference();

            if (!(reference instanceof MethodReference)) {
                continue;
            }

            MethodReference methodReference =
                    (MethodReference) reference;

            if (!FINGERPRINT_METHOD.equals(
                    methodReference.getName())) {
                continue;
            }

            if (!"()Ljava/lang/String;".equals(
                    buildMethodSignature(methodReference))) {
                continue;
            }

            return i;
        }

        return -1;
    }

    private static String buildMethodSignature(
            MethodReference methodReference
    ) {

        StringBuilder result =
                new StringBuilder();

        result.append("(");

        for (CharSequence parameter :
                methodReference.getParameterTypes()) {

            result.append(parameter);
        }

        result.append(")");
        result.append(methodReference.getReturnType());

        return result.toString();
    }

    private static List<Method> toMethodList(
            Iterable<? extends Method> methods
    ) {

        List<Method> result =
                new ArrayList<>();

        for (Method method : methods) {
            result.add(method);
        }

        return result;
    }

    private static List<Instruction> toInstructionList(
            Iterable<? extends Instruction> instructions
    ) {

        List<Instruction> result =
                new ArrayList<>();

        for (Instruction instruction : instructions) {
            result.add(instruction);
        }

        return result;
    }

    private static void rebuildApk(
            Path input,
            Path output,
            Map<String, byte[]> replacementDex
    ) throws IOException {

        try (
                ZipInputStream zis =
                        new ZipInputStream(
                                new BufferedInputStream(
                                        Files.newInputStream(input)
                                )
                        );

                ZipOutputStream zos =
                        new ZipOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(output)
                                )
                        )
        ) {

            ZipEntry entry;

            byte[] buffer =
                    new byte[1024 * 1024];

            while ((entry =
                    zis.getNextEntry()) != null) {

                String name =
                        entry.getName();

                /*
                 * Remove old APK signing files.
                 * The APK will be signed again.
                 */
                if (isSignatureFile(name)) {
                    continue;
                }

                ZipEntry newEntry =
                        new ZipEntry(name);

                newEntry.setTime(
                        entry.getTime()
                );

                zos.putNextEntry(newEntry);

                byte[] replacement =
                        replacementDex.get(name);

                if (replacement != null) {

                    zos.write(replacement);

                } else {

                    int read;

                    while ((read =
                            zis.read(buffer)) != -1) {

                        zos.write(
                                buffer,
                                0,
                                read
                        );
                    }
                }

                zos.closeEntry();
            }
        }
    }

    private static boolean isSignatureFile(
            String name
    ) {

        if (!name.startsWith("META-INF/")) {
            return false;
        }

        String upper =
                name.toUpperCase(Locale.ROOT);

        return upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")
                || upper.equals(
                        "META-INF/MANIFEST.MF"
                );
    }

    private static void deleteRecursively(
            Path path
    ) throws IOException {

        if (!Files.exists(path)) {
            return;
        }

        try (
                var stream =
                        Files.walk(path)
        ) {

            stream
                    .sorted(
                            Comparator.reverseOrder()
                    )
                    .forEach(p -> {

                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }

                    });
        }
    }

    private static final class PatchMethodResult {

        final Method method;
        final boolean fingerprintPatched;
        final boolean headerPatched;

        PatchMethodResult(
                Method method,
                boolean fingerprintPatched,
                boolean headerPatched
        ) {

            this.method =
                    method;

            this.fingerprintPatched =
                    fingerprintPatched;

            this.headerPatched =
                    headerPatched;
        }

        static PatchMethodResult unchanged(
                Method method
        ) {

            return new PatchMethodResult(
                    method,
                    false,
                    false
            );
        }
    }

    private static final class PatchResult {

        final Set<ClassDef> classes;
        final boolean fingerprintPatched;
        final boolean headerPatched;
        final boolean changed;

        PatchResult(
                Set<ClassDef> classes,
                boolean fingerprintPatched,
                boolean headerPatched,
                boolean changed
        ) {

            this.classes =
                    classes;

            this.fingerprintPatched =
                    fingerprintPatched;

            this.headerPatched =
                    headerPatched;

            this.changed =
                    changed;
        }
    }
}
