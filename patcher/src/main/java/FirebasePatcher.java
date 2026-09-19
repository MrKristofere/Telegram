import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.ReferenceInstruction;

import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;

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

    /*
     * Firebase versions may use slightly different capitalization.
     * Your APK uses openHttpURLConnection.
     */
    private static final String CONNECTION_METHOD =
            "openHttpURLConnection";

    private static final String CONNECTION_METHOD_ALT =
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
                        "Scanning " + dexEntryName + "..."
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
                        "  -> modified: " + dexEntryName
                );
            }

            /*
             * IMPORTANT:
             *
             * At least ONE of the two fixes must succeed.
             *
             * Fix 1:
             * getFingerprintHashForPackage()
             *
             * Fix 2:
             * X-Android-Cert request value
             */
            if (!fingerprintPatched && !headerPatched) {

                throw new IllegalStateException(
                        "Firebase targets were not found. " +
                        "Neither getFingerprintHashForPackage() nor " +
                        "the X-Android-Cert request could be patched."
                );
            }

            System.out.println();
            System.out.println(
                    "getFingerprintHashForPackage(): " +
                    (fingerprintPatched
                            ? "PATCHED"
                            : "not found")
            );

            System.out.println(
                    "X-Android-Cert request: " +
                    (headerPatched
                            ? "PATCHED"
                            : "not found")
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

            boolean classChanged = false;

            List<Method> directMethods =
                    new ArrayList<>();

            List<Method> virtualMethods =
                    new ArrayList<>();

            /*
             * Direct methods
             */
            for (Method method : classDef.getDirectMethods()) {

                Method patched =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (patched != method) {

                    classChanged = true;

                    if (FINGERPRINT_METHOD.equals(
                            method.getName()
                    )) {
                        fingerprintPatched = true;

                        System.out.println(
                                "  [Fix 1] Patched " +
                                classDef.getType() +
                                "->" +
                                method.getName()
                        );
                    }

                    if (containsCertificateHeader(method)) {
                        headerPatched = true;

                        System.out.println(
                                "  [Fix 2] Patched " +
                                classDef.getType() +
                                "->" +
                                method.getName()
                        );
                    }
                }

                directMethods.add(patched);
            }

            /*
             * Virtual methods
             */
            for (Method method : classDef.getVirtualMethods()) {

                Method patched =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (patched != method) {

                    classChanged = true;

                    if (FINGERPRINT_METHOD.equals(
                            method.getName()
                    )) {
                        fingerprintPatched = true;

                        System.out.println(
                                "  [Fix 1] Patched " +
                                classDef.getType() +
                                "->" +
                                method.getName()
                        );
                    }

                    if (containsCertificateHeader(method)) {
                        headerPatched = true;

                        System.out.println(
                                "  [Fix 2] Patched " +
                                classDef.getType() +
                                "->" +
                                method.getName()
                        );
                    }
                }

                virtualMethods.add(patched);
            }

            /*
             * Preserve every class.
             * Replace only modified classes.
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

    private static Method patchMethod(
            Method method,
            boolean isFirebaseClass,
            String hash
    ) {

        MethodImplementation implementation =
                method.getImplementation();

        if (implementation == null) {
            return method;
        }

        /*
         * ============================================================
         * FIX 1
         * ============================================================
         *
         * Replace:
         *
         * getFingerprintHashForPackage()
         *
         * with:
         *
         * const-string v0, "<SHA1>"
         * return-object v0
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

            return new ImmutableMethod(
                    method.getDefiningClass(),
                    method.getName(),
                    method.getParameters(),
                    method.getReturnType(),
                    method.getAccessFlags(),
                    method.getAnnotations(),
                    method.getHiddenApiRestrictions(),
                    replacement
            );
        }

        /*
         * ============================================================
         * FIX 2
         * ============================================================
         *
         * Exact structure from your APK:
         *
         * const-string v0, "X-Android-Cert"
         *
         * invoke-direct {p0},
         *   ...->getFingerprintHashForPackage()Ljava/lang/String;
         *
         * move-result-object v1
         *
         * invoke-virtual {p1, v0, v1},
         *   Ljava/net/HttpURLConnection;->addRequestProperty(...)
         *
         * We insert:
         *
         * const-string v1, "<SHA1>"
         *
         * immediately AFTER move-result-object.
         *
         * This means we do not depend on a hardcoded v1.
         * The destination register is read from the DEX.
         */
        if (isConnectionMethod(method.getName())) {

            List<Instruction> instructions =
                    toList(
                            implementation.getInstructions()
                    );

            int headerIndex = -1;

            /*
             * Find:
             *
             * const-string ..., "X-Android-Cert"
             */
            for (int i = 0; i < instructions.size(); i++) {

                Instruction instruction =
                        instructions.get(i);

                if (!(instruction instanceof ReferenceInstruction)) {
                    continue;
                }

                Object reference =
                        ((ReferenceInstruction) instruction)
                                .getReference();

                if (!(reference instanceof StringReference)) {
                    continue;
                }

                String value =
                        ((StringReference) reference)
                                .getString();

                if (CERT_HEADER.equals(value)) {

                    headerIndex = i;

                    System.out.println(
                            "  [Fix 2] Found X-Android-Cert in " +
                            method.getDefiningClass() +
                            "->" +
                            method.getName()
                    );

                    break;
                }
            }

            if (headerIndex < 0) {
                return method;
            }

            /*
             * Search forward for:
             *
             * invoke-direct ... getFingerprintHashForPackage()
             */
            int fingerprintInvokeIndex = -1;

            for (
                    int i = headerIndex + 1;
                    i < instructions.size();
                    i++
            ) {

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
                        methodReference.getName()
                )) {
                    continue;
                }

                if (!"Ljava/lang/String;".equals(
                        methodReference.getReturnType()
                )) {
                    continue;
                }

                fingerprintInvokeIndex = i;
                break;
            }

            if (fingerprintInvokeIndex < 0) {

                System.out.println(
                        "  [Fix 2] getFingerprintHashForPackage() " +
                        "call not found after X-Android-Cert"
                );

                return method;
            }

            /*
             * The next instruction must be:
             *
             * move-result-object vX
             */
            int moveResultIndex =
                    fingerprintInvokeIndex + 1;

            if (moveResultIndex >= instructions.size()) {
                return method;
            }

            Instruction moveResult =
                    instructions.get(moveResultIndex);

            if (moveResult.getOpcode() !=
                    Opcode.MOVE_RESULT_OBJECT) {

                System.out.println(
                        "  [Fix 2] Expected move-result-object " +
                        "after getFingerprintHashForPackage()"
                );

                return method;
            }

            if (!(moveResult instanceof OneRegisterInstruction)) {

                System.out.println(
                        "  [Fix 2] move-result-object does not " +
                        "expose a destination register"
                );

                return method;
            }

            int valueRegister =
                    ((OneRegisterInstruction) moveResult)
                            .getRegisterA();

            /*
             * Verify that the register really reaches
             * addRequestProperty().
             *
             * We search a short distance forward because the
             * Firebase method has the exact sequence:
             *
             * move-result-object
             * invoke-virtual addRequestProperty
             */
            int requestPropertyIndex = -1;

            for (
                    int i = moveResultIndex + 1;
                    i < instructions.size() &&
                    i <= moveResultIndex + 8;
                    i++
            ) {

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
                        methodReference.getName()
                )) {
                    continue;
                }

                if (!(instruction instanceof FiveRegisterInstruction)) {

                    System.out.println(
                            "  [Fix 2] addRequestProperty() is not " +
                            "a five-register instruction"
                    );

                    return method;
                }

                FiveRegisterInstruction invoke =
                        (FiveRegisterInstruction) instruction;

                /*
                 * In:
                 *
                 * invoke-virtual {p1, v0, v1}, ...
                 *
                 * v1 is registerE.
                 */
                int requestValueRegister =
                        invoke.getRegisterE();

                if (requestValueRegister != valueRegister) {

                    System.out.println(
                            "  [Fix 2] Register mismatch: " +
                            "move-result=v" + valueRegister +
                            ", addRequestProperty=v" +
                            requestValueRegister
                    );

                    return method;
                }

                requestPropertyIndex = i;
                break;
            }

            if (requestPropertyIndex < 0) {

                System.out.println(
                        "  [Fix 2] addRequestProperty() was not " +
                        "found after getFingerprintHashForPackage()"
                );

                return method;
            }

            /*
             * Insert:
             *
             * const-string vX, "<SHA1>"
             *
             * immediately AFTER move-result-object.
             *
             * This leaves the original Firebase call intact,
             * but overwrites its result before it is sent.
             */
            MutableMethodImplementation mutable =
                    new MutableMethodImplementation(
                            implementation
                    );

            mutable.addInstruction(
                    moveResultIndex + 1,
                    new BuilderInstruction21c(
                            Opcode.CONST_STRING,
                            valueRegister,
                            new ImmutableStringReference(hash)
                    )
            );

            System.out.println(
                    "  [Fix 2] Injected SHA-1 into v" +
                    valueRegister +
                    " before X-Android-Cert request"
            );

            return new ImmutableMethod(
                    method.getDefiningClass(),
                    method.getName(),
                    method.getParameters(),
                    method.getReturnType(),
                    method.getAccessFlags(),
                    method.getAnnotations(),
                    method.getHiddenApiRestrictions(),
                    mutable
            );
        }

        return method;
    }

    private static boolean isConnectionMethod(
            String name
    ) {
        return CONNECTION_METHOD.equals(name)
                || CONNECTION_METHOD_ALT.equals(name);
    }

    private static boolean containsCertificateHeader(
            Method method
    ) {

        MethodImplementation implementation =
                method.getImplementation();

        if (implementation == null) {
            return false;
        }

        for (Instruction instruction :
                implementation.getInstructions()) {

            if (!(instruction instanceof ReferenceInstruction)) {
                continue;
            }

            Object reference =
                    ((ReferenceInstruction) instruction)
                            .getReference();

            if (!(reference instanceof StringReference)) {
                continue;
            }

            if (CERT_HEADER.equals(
                    ((StringReference) reference).getString()
            )) {
                return true;
            }
        }

        return false;
    }

    private static List<Instruction> toList(
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
                 * Remove old APK signing files.
                 * APK will be signed again later.
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

                    while (
                            (read = zis.read(buffer)) != -1
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
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            }
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
