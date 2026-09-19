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
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
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

    private static final String CERT_HEADER = "X-Android-Cert";
    private static final String ADD_REQUEST_PROPERTY = "addRequestProperty";

    /*
     * Fix 1 anchors.
     *
     * We intentionally do NOT depend on:
     *
     * FirebaseInstallationServiceClient
     * getFingerprintHashForPackage
     *
     * Instead we identify the method structurally by the two Firebase
     * certificate-related calls found in the current APK.
     */
    private static final String GET_CERT_BYTES =
            "getPackageCertificateHashBytes";

    private static final String BYTES_TO_HEX =
            "bytesToStringUppercase";

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

        System.out.println("========================================");
        System.out.println(" Firebase APK Patcher");
        System.out.println("========================================");
        System.out.println("Input APK : " + input);
        System.out.println("Output APK: " + output);
        System.out.println("Firebase SHA-1: " + hash);
        System.out.println();

        patchApk(input, output, hash);

        System.out.println();
        System.out.println("========================================");
        System.out.println(" Firebase patch completed");
        System.out.println("========================================");
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

        Map<String, byte[]> replacementDex =
                new HashMap<>();

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

                System.out.println();
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
                        patchDex(
                                dexFile,
                                hash
                        );

                if (!result.changed) {
                    System.out.println(
                            "  no changes"
                    );
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
             * We expect BOTH Firebase patches to succeed.
             *
             * Fix 1 changes the value returned by the Firebase
             * certificate calculation.
             *
             * Fix 2 changes the actual X-Android-Cert HTTP header
             * value at the request call site.
             */
            if (!fingerprintPatched) {
                throw new IllegalStateException(
                        "Fix 1 failed: Firebase certificate " +
                        "fingerprint method was not found."
                );
            }

            if (!headerPatched) {
                throw new IllegalStateException(
                        "Fix 2 failed: X-Android-Cert request " +
                        "was not found."
                );
            }

            System.out.println();
            System.out.println(
                    "[RESULT] getFingerprintHashForPackage(): " +
                    (fingerprintPatched
                            ? "patched"
                            : "NOT PATCHED")
            );

            System.out.println(
                    "[RESULT] HttpURLConnection (X-Android-Cert): " +
                    (headerPatched
                            ? "patched"
                            : "NOT PATCHED")
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

            List<Method> directMethods =
                    toMethodList(
                            classDef.getDirectMethods()
                    );

            List<Method> virtualMethods =
                    toMethodList(
                            classDef.getVirtualMethods()
                    );

            boolean classChanged = false;

            /*
             * Direct methods.
             */
            for (int i = 0;
                 i < directMethods.size();
                 i++) {

                Method method =
                        directMethods.get(i);

                PatchMethodResult result =
                        patchMethod(
                                method,
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
            for (int i = 0;
                 i < virtualMethods.size();
                 i++) {

                Method method =
                        virtualMethods.get(i);

                PatchMethodResult result =
                        patchMethod(
                                method,
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
             * Rebuild class.
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
            String hash
    ) {

        MethodImplementation implementation =
                method.getImplementation();

        if (implementation == null) {
            return PatchMethodResult.unchanged(
                    method
            );
        }

        List<Instruction> instructions =
                toInstructionList(
                        implementation.getInstructions()
                );

        /*
         * ============================================================
         * FIX 1
         * ============================================================
         *
         * Current Firebase code contains:
         *
         * AndroidUtilsLight.getPackageCertificateHashBytes(...)
         *
         * followed by:
         *
         * Hex.bytesToStringUppercase(...)
         *
         * We locate the method containing both calls.
         *
         * We then replace its entire implementation with:
         *
         * const-string v0, "<SHA1>"
         * return-object v0
         *
         * This removes the dependency on the APK's actual signing
         * certificate.
         */

        if (isFingerprintMethod(instructions)
                && "Ljava/lang/String;".equals(
                        method.getReturnType()
                )) {

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
                            new ImmutableStringReference(
                                    hash
                            )
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

            System.out.println(
                    "  [Fix 1] Patched fingerprint method: " +
                    method.getDefiningClass() +
                    "->" +
                    method.getName()
            );

            /*
             * We return here because this method is the Firebase
             * fingerprint method, not the HTTP request builder.
             */
            return new PatchMethodResult(
                    patchedMethod,
                    true,
                    false
            );
        }

        /*
         * ============================================================
         * FIX 2
         * ============================================================
         *
         * We DO NOT depend on the method name.
         *
         * We search directly for:
         *
         * const-string vX, "X-Android-Cert"
         *
         * and then the next:
         *
         * invoke-virtual {...}, ...->addRequestProperty(
         *     String,
         *     String
         * )V
         *
         * For:
         *
         * invoke-virtual {p1, v0, v1}, ...
         *
         * registerE == v1
         *
         * We insert:
         *
         * const-string v1, "<SHA1>"
         *
         * immediately before addRequestProperty().
         */

        int headerIndex =
                findCertificateHeaderString(
                        instructions
                );

        if (headerIndex >= 0) {

            int requestPropertyIndex =
                    findAddRequestPropertyAfter(
                            instructions,
                            headerIndex
                    );

            if (requestPropertyIndex >= 0) {

                Instruction requestInstruction =
                        instructions.get(
                                requestPropertyIndex
                        );

                if (!(requestInstruction
                        instanceof FiveRegisterInstruction)) {

                    throw new IllegalStateException(
                            "Fix 2 found addRequestProperty() " +
                            "but instruction is not " +
                            "FiveRegisterInstruction in " +
                            method.getDefiningClass() +
                            "->" +
                            method.getName()
                    );
                }

                int valueRegister =
                        ((FiveRegisterInstruction)
                                requestInstruction)
                                .getRegisterE();

                if (valueRegister < 0
                        || valueRegister > 255) {

                    throw new IllegalStateException(
                            "Fix 2 value register v" +
                            valueRegister +
                            " cannot be used by const-string/21c"
                    );
                }

                MutableMethodImplementation mutable =
                        new MutableMethodImplementation(
                                implementation
                        );

                mutable.addInstruction(
                        requestPropertyIndex,
                        new BuilderInstruction21c(
                                Opcode.CONST_STRING,
                                valueRegister,
                                new ImmutableStringReference(
                                        hash
                                )
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
                        "  [Fix 2] Patched X-Android-Cert in: " +
                        method.getDefiningClass() +
                        "->" +
                        method.getName() +
                        " using v" +
                        valueRegister
                );

                return new PatchMethodResult(
                        patchedMethod,
                        false,
                        true
                );
            }
        }

        return PatchMethodResult.unchanged(
                method
        );
    }

    /*
     * Determines whether a method is the Firebase certificate
     * fingerprint method.
     *
     * We require BOTH:
     *
     * getPackageCertificateHashBytes
     *
     * and:
     *
     * bytesToStringUppercase
     *
     * This prevents accidentally patching unrelated methods.
     */
    private static boolean isFingerprintMethod(
            List<Instruction> instructions
    ) {

        boolean foundCertificateBytes = false;
        boolean foundHexConversion = false;

        for (Instruction instruction : instructions) {

            if (!(instruction instanceof
                    org.jf.dexlib2.iface.instruction.ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                            instruction)
                            .getReference();

            if (!(reference instanceof MethodReference)) {
                continue;
            }

            MethodReference method =
                    (MethodReference) reference;

            String definingClass =
                    method.getDefiningClass();

            String name =
                    method.getName();

            List<? extends CharSequence> parameters =
                    method.getParameterTypes();

            String returnType =
                    method.getReturnType();

            if (definingClass.equals(
                    "Lcom/google/android/gms/common/util/AndroidUtilsLight;"
            )
                    && name.equals(
                    "getPackageCertificateHashBytes"
            )
                    && parameters.size() == 2
                    && "Landroid/content/Context;".contentEquals(
                    parameters.get(0)
            )
                    && "Ljava/lang/String;".contentEquals(
                    parameters.get(1)
            )
                    && "[B".equals(returnType)) {

                foundCertificateBytes = true;
            }

            if (definingClass.equals(
                    "Lcom/google/android/gms/common/util/Hex;"
            )
                    && name.equals(
                    "bytesToStringUppercase"
            )
                    && parameters.size() == 2
                    && "[B".equals(
                    parameters.get(0)
            )
                    && "Z".equals(
                    parameters.get(1)
            )
                    && "Ljava/lang/String;".equals(
                    returnType
            )) {

                foundHexConversion = true;
            }
        }

        return foundCertificateBytes
                && foundHexConversion;
    }
    private static int findCertificateHeaderString(
            List<Instruction> instructions
    ) {

        for (int i = 0;
             i < instructions.size();
             i++) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction
                    instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((org.jf.dexlib2.iface.instruction.ReferenceInstruction)
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

    private static int findAddRequestPropertyAfter(
            List<Instruction> instructions,
            int anchorIndex
    ) {

        for (int i = anchorIndex + 1;
             i < instructions.size();
             i++) {

            Instruction instruction =
                    instructions.get(i);

            if (!(instruction
                    instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((org.jf.dexlib2.iface.instruction.ReferenceInstruction)
                            instruction)
                            .getReference();

            if (!(reference instanceof MethodReference)) {
                continue;
            }

            MethodReference methodReference =
                    (MethodReference) reference;

            if (!ADD_REQUEST_PROPERTY.equals(
                    methodReference.getName()
            )) {
                continue;
            }

            /*
             * We specifically want:
             *
             * addRequestProperty(String, String)
             */
            List<? extends CharSequence> parameters =
                    methodReference.getParameterTypes();

            if (parameters.size() != 2) {
                continue;
            }

            if (!"Ljava/lang/String;".contentEquals(
                    parameters.get(0)
            )) {
                continue;
            }

            if (!"Ljava/lang/String;".contentEquals(
                    parameters.get(1)
            )) {
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

            while (
                    (entry = zis.getNextEntry()) != null
            ) {

                String name =
                        entry.getName();

                /*
                 * Remove the old APK signatures.
                 *
                 * apksigner will sign the resulting APK later.
                 */
                if (isSignatureFile(name)) {
                    continue;
                }

                ZipEntry newEntry =
                        new ZipEntry(name);

                newEntry.setTime(
                        entry.getTime()
                );

                zos.putNextEntry(
                        newEntry
                );

                byte[] replacement =
                        replacementDex.get(name);

                if (replacement != null) {

                    zos.write(
                            replacement
                    );

                } else {

                    int read;

                    while (
                            (read = zis.read(buffer))
                                    != -1
                    ) {

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
                name.toUpperCase(
                        Locale.ROOT
                );

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
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(
                                            p
                                    );
                                } catch (IOException ignored) {
                                }
                            }
                    );
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
