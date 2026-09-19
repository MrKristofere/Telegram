import org.jf.dexlib2.DexFileFactory;
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

    private static final String CERT_HEADER =
            "X-Android-Cert";

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
             * Success if at least one of the two patches was applied.
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
             * Rebuild only classes that changed.
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
         * Replace the entire implementation with:
         *
         *     const-string v0, "SHA1"
         *     return-object v0
         *
         * This is the primary patch.
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
         * Morphe-style call-site patch.
         *
         * Preferred anchor:
         *
         *     "X-Android-Cert"
         *
         * If the literal is present in this method, use its index
         * exactly like the Morphe patch.
         *
         * However, in your current Firebase APK the Java source is:
         *
         *     addRequestProperty(
         *         X_ANDROID_CERT_HEADER_KEY,
         *         getFingerprintHashForPackage()
         *     );
         *
         * X_ANDROID_CERT_HEADER_KEY is a field, so the literal
         * "X-Android-Cert" is NOT necessarily present in this method.
         *
         * Therefore we use a fallback anchor:
         *
         *     getFingerprintHashForPackage()
         *
         * and then find the next addRequestProperty().
         *
         * We intentionally do NOT require move-result-object or
         * inspect the data flow between the two instructions.
         *
         * This follows the same basic strategy as Morphe:
         *
         *     anchor -> next addRequestProperty -> registerE
         * ============================================================
         */

        if (CONNECTION_METHOD.equals(method.getName())) {

            List<Instruction> instructions =
                    toInstructionList(
                            implementation.getInstructions()
                    );

            /*
             * First try the literal "X-Android-Cert".
             */
            int anchorIndex =
                    findCertificateHeaderString(
                            instructions
                    );

            String anchorType;

            if (anchorIndex >= 0) {
                anchorType = "X-Android-Cert string";
            } else {
                /*
                 * Fallback for your current APK:
                 *
                 * find getFingerprintHashForPackage()
                 */
                anchorIndex =
                        findFingerprintInvocation(
                                instructions
                        );

                anchorType =
                        "getFingerprintHashForPackage()";
            }

            if (anchorIndex < 0) {

                System.out.println(
                        "  Fix 2: no Firebase certificate anchor in " +
                        method.getDefiningClass() +
                        "->" +
                        method.getName()
                );

                return PatchMethodResult.unchanged(method);
            }

            /*
             * Exactly like Morphe:
             *
             * start AFTER the anchor
             * find the first addRequestProperty()
             */
            int requestPropertyIndex =
                    findAddRequestPropertyAfter(
                            instructions,
                            anchorIndex
                    );

            if (requestPropertyIndex < 0) {

                System.out.println(
                        "  Fix 2: anchor found (" +
                        anchorType +
                        "), but addRequestProperty() was not found after it."
                );

                return PatchMethodResult.unchanged(method);
            }

            Instruction addRequestProperty =
                    instructions.get(
                            requestPropertyIndex
                    );

            /*
             * Morphe uses FiveRegisterInstruction.registerE.
             *
             * This corresponds to:
             *
             *     invoke-virtual {
             *         vConnection,
             *         vHeader,
             *         vValue
             *     }, addRequestProperty(...)
             *
             * registerE = vValue
             */
            if (!(addRequestProperty
                    instanceof FiveRegisterInstruction)) {

                System.out.println(
                        "  Fix 2: addRequestProperty() is not a " +
                        "FiveRegisterInstruction; skipping."
                );

                return PatchMethodResult.unchanged(method);
            }

            int valueRegister =
                    ((FiveRegisterInstruction)
                            addRequestProperty)
                            .getRegisterE();

            /*
             * CONST_STRING is a 21c instruction and therefore
             * can only address v0..v255.
             *
             * registerE from a normal invoke-virtual (35c)
             * is only 4-bit, so v0..v15.
             */
            if (valueRegister < 0 ||
                    valueRegister > 15) {

                System.out.println(
                        "  Fix 2: invalid register v" +
                        valueRegister +
                        "; skipping."
                );

                return PatchMethodResult.unchanged(method);
            }

            /*
             * Insert directly BEFORE addRequestProperty().
             *
             * This is the same operation as the Morphe patch:
             *
             *     method.addInstruction(
             *         insertIndex,
             *         "const-string v$valueRegister, \"$hash\""
             *     )
             */
            MutableMethodImplementation mutable =
                    new MutableMethodImplementation(
                            implementation
                    );

            mutable.addInstruction(
                    requestPropertyIndex,
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

            System.out.println(
                    "  Fix 2: patched " +
                    anchorType +
                    " -> addRequestProperty(), " +
                    "value register v" +
                    valueRegister
            );

            return new PatchMethodResult(
                    patchedMethod,
                    false,
                    true
            );
        }

        return PatchMethodResult.unchanged(method);
    }

    /*
     * Find the literal:
     *
     *     "X-Android-Cert"
     *
     * inside the current method.
     */
    private static int findCertificateHeaderString(
            List<Instruction> instructions
    ) {

        for (int i = 0; i < instructions.size(); i++) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }

            Object reference =
                    ((ReferenceInstruction)
                            instruction)
                            .getReference();

            if (!(reference instanceof StringReference)) {
                continue;
            }

            String value =
                    ((StringReference) reference)
                            .getString();

            if (CERT_HEADER.equals(value)) {
                return i;
            }
        }

        return -1;
    }

    /*
     * Find:
     *
     *     invoke-virtual {...},
     *         getFingerprintHashForPackage()Ljava/lang/String;
     *
     * inside the current method.
     */
    private static int findFingerprintInvocation(
            List<Instruction> instructions
    ) {

        for (int i = 0; i < instructions.size(); i++) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }

            Object reference =
                    ((ReferenceInstruction)
                            instruction)
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

            if (!methodReference.getParameterTypes().isEmpty()) {
                continue;
            }

            if (!"Ljava/lang/String;".equals(
                    methodReference.getReturnType())) {
                continue;
            }

            return i;
        }

        return -1;
    }

    /*
     * Morphe-style:
     *
     *     instructions.drop(anchorIndex)
     *         .firstOrNull { invoke-virtual addRequestProperty }
     *
     * We deliberately do not inspect how the arguments were produced.
     */
    private static int findAddRequestPropertyAfter(
            List<Instruction> instructions,
            int anchorIndex
    ) {

        for (int i = anchorIndex + 1;
             i < instructions.size();
             i++) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }

            Object reference =
                    ((ReferenceInstruction)
                            instruction)
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
             * addRequestProperty(String, String)
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

            return i;
        }

        return -1;
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
