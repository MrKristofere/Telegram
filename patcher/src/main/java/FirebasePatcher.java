import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
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

                /*
                 * Only rewrite a DEX if something inside it was changed.
                 */
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
                    "X-Android-Cert: " +
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

        /*
         * IMPORTANT:
         * Keep ALL classes from the original DEX.
         * Only replace the classes that were actually modified.
         */
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

            for (Method method : classDef.getDirectMethods()) {

                Method patched =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (patched != method) {
                    classChanged = true;

                    if (FINGERPRINT_METHOD.equals(method.getName())) {
                        fingerprintPatched = true;
                    }

                    if (containsCertificateHeader(method)) {
                        headerPatched = true;
                    }
                }

                directMethods.add(patched);
            }

            for (Method method : classDef.getVirtualMethods()) {

                Method patched =
                        patchMethod(
                                method,
                                isFirebaseClass,
                                hash
                        );

                if (patched != method) {
                    classChanged = true;

                    if (FINGERPRINT_METHOD.equals(method.getName())) {
                        fingerprintPatched = true;
                    }

                    if (containsCertificateHeader(method)) {
                        headerPatched = true;
                    }
                }

                virtualMethods.add(patched);
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

                /*
                 * Preserve untouched classes exactly as they were.
                 */
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
         * Fix 1:
         *
         * FirebaseInstallationServiceClient
         * -> getFingerprintHashForPackage()
         *
         * Replace implementation with:
         *
         * const-string v0, "SHA1"
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
         * Fix 2:
         *
         * Find "X-Android-Cert" and the following
         * addRequestProperty(...).
         *
         * Replace the value register immediately before
         * addRequestProperty() with our SHA-1.
         */
        if (CONNECTION_METHOD.equals(method.getName())) {

            List<Instruction> instructions =
                    toList(implementation.getInstructions());

            int headerIndex = -1;
            int requestPropertyIndex = -1;
            int valueRegister = -1;

            for (int i = 0; i < instructions.size(); i++) {

                Instruction instruction =
                        instructions.get(i);

                if (!(instruction instanceof ReferenceInstruction)) {
                    continue;
                }

                Object reference =
                        ((ReferenceInstruction) instruction)
                                .getReference();

                if (reference instanceof StringReference) {

                    String value =
                            ((StringReference) reference)
                                    .getString();

                    if (CERT_HEADER.equals(value)) {
                        headerIndex = i;
                        break;
                    }
                }
            }

            if (headerIndex >= 0) {

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

                    if (!ADD_REQUEST_PROPERTY.equals(
                            methodReference.getName())) {
                        continue;
                    }

                    if (!(instruction instanceof FiveRegisterInstruction)) {
                        throw new IllegalStateException(
                                "addRequestProperty() is not a " +
                                "five-register instruction in " +
                                method.getDefiningClass() +
                                "->" +
                                method.getName()
                        );
                    }

                    valueRegister =
                            ((FiveRegisterInstruction) instruction)
                                    .getRegisterE();

                    requestPropertyIndex = i;
                    break;
                }
            }

            if (requestPropertyIndex >= 0) {

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
        }

        return method;
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

            if (reference instanceof StringReference) {

                if (CERT_HEADER.equals(
                        ((StringReference) reference).getString()
                )) {
                    return true;
                }
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

            while ((entry = zis.getNextEntry()) != null) {

                String name = entry.getName();

                /*
                 * Remove old APK signing files.
                 * The APK will be signed again later.
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
                || upper.equals("META-INF/MANIFEST.MF");
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
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {

                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }

                    });
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

            this.classes = classes;

            this.fingerprintPatched =
                    fingerprintPatched;

            this.headerPatched =
                    headerPatched;

            this.changed =
                    changed;
        }
    }
}
