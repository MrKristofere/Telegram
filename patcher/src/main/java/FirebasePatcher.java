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
                    System.out.println("  no changes");
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

            if (!fingerprintPatched) {
                throw new IllegalStateException(
                        "Fix 1 failed: Firebase certificate " +
                        "fingerprint code was not found."
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
                    "[RESULT] Firebase certificate: patched"
            );
            System.out.println(
                    "[RESULT] X-Android-Cert request: patched"
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
         * Current APK certificate calculation:
         *
         * PackageInfo.signatures
         *       |
         *       v
         * Signature.toByteArray()
         *       |
         *       v
         * MessageDigest.getInstance("SHA1")
         *       |
         *       v
         * MessageDigest.digest()
         *       |
         *       v
         * hex String
         *
         * The current APK does NOT use:
         *
         * getPackageCertificateHashBytes()
         *
         * as a separate Firebase method.
         *
         * Therefore we identify the calculation structurally.
         *
         * We do NOT replace the whole method because the same method
         * also creates/configures HttpURLConnection.
         */

        boolean certificateGeneration =
                isCertificateGenerationMethod(
                        instructions
                );

        int headerIndex =
                findCertificateHeaderString(
                        instructions
                );

        if (certificateGeneration
                && headerIndex >= 0) {

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
                            "Fix 1 found X-Android-Cert but " +
                            "addRequestProperty() is not " +
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
                            "Fix 1 value register v" +
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
                        "  [Fix 1] Certificate calculation found in: " +
                        method.getDefiningClass() +
                        "->" +
                        method.getName()
                );

                System.out.println(
                        "  [Fix 1] Forced SHA-1 into X-Android-Cert, v" +
                        valueRegister
                );

                return new PatchMethodResult(
                        patchedMethod,
                        true,
                        true
                );
            }
        }

        /*
         * ============================================================
         * FIX 2
         * ============================================================
         *
         * Generic fallback.
         *
         * Search:
         *
         *     const-string ..., "X-Android-Cert"
         *
         * followed by:
         *
         *     addRequestProperty(String, String)
         *
         * Then replace only the value register.
         *
         * This does not depend on Firebase class/method names.
         */

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
                            "Fix 2 found X-Android-Cert but " +
                            "addRequestProperty() is not " +
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
                        "  [Fix 2] Found X-Android-Cert in: " +
                        method.getDefiningClass() +
                        "->" +
                        method.getName()
                );

                System.out.println(
                        "  [Fix 2] Patched X-Android-Cert request, v" +
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

    private static boolean isCertificateGenerationMethod(
            List<Instruction> instructions
    ) {
        boolean hasPackageInfoSignatures = false;
        boolean hasSha1 = false;
        boolean hasMessageDigestGetInstance = false;
        boolean hasSignatureToByteArray = false;
        boolean hasMessageDigestDigest = false;

        for (Instruction instruction : instructions) {

            if (!(instruction
                    instanceof ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((ReferenceInstruction)
                            instruction)
                            .getReference();

            String referenceText =
                    String.valueOf(reference);

            /*
             * PackageInfo.signatures is a FIELD reference.
             */
            if (referenceText.contains(
                    "Landroid/content/pm/PackageInfo;->signatures:"
            )) {
                hasPackageInfoSignatures = true;
            }

            /*
             * Signature.toByteArray()
             */
            if (referenceText.contains(
                    "Landroid/content/pm/Signature;->toByteArray()"
            )) {
                hasSignatureToByteArray = true;
            }

            /*
             * MessageDigest methods.
             */
            if (reference instanceof MethodReference) {

                MethodReference methodReference =
                        (MethodReference) reference;

                String definingClass =
                        methodReference.getDefiningClass();

                String name =
                        methodReference.getName();

                if ("Ljava/security/MessageDigest;"
                        .equals(definingClass)
                        && "getInstance".equals(name)) {

                    hasMessageDigestGetInstance = true;
                }

                if ("Ljava/security/MessageDigest;"
                        .equals(definingClass)
                        && "digest".equals(name)) {

                    hasMessageDigestDigest = true;
                }
            }

            /*
             * SHA1 string.
             */
            if (reference instanceof StringReference) {

                String value =
                        ((StringReference)
                                reference)
                                .getString();

                if ("SHA1".equals(value)) {
                    hasSha1 = true;
                }
            }
        }

        return hasPackageInfoSignatures
                && hasSha1
                && hasMessageDigestGetInstance
                && hasSignatureToByteArray
                && hasMessageDigestDigest;
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
                    instanceof ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((ReferenceInstruction)
                            instruction)
                            .getReference();

            if (!(reference instanceof StringReference)) {
                continue;
            }

            String value =
                    ((StringReference)
                            reference)
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
                    instanceof ReferenceInstruction)) {
                continue;
            }

            Reference reference =
                    ((ReferenceInstruction)
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
