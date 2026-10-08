import jdk.internal.org.objectweb.asm.*;
import java.nio.file.*;

/** Preserve the shipped desktop UI while adding the shared update button. */
public final class HouseDesktopUpdateInjector {
    public static void main(String[] args) throws Exception {
        byte[] input = Files.readAllBytes(Path.of(args[0]));
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] inserted = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM8, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("<init>")) return delegate;
                return new MethodVisitor(Opcodes.ASM8, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (method.equals("pack") && descriptor.equals("()V")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/housecommander/desktop/DesktopCardUpdates", "install", "(Ljavax/swing/JFrame;)V", false);
                            inserted[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                    }
                };
            }
        }, 0);
        if (inserted[0] != 1) throw new IllegalStateException("Expected one desktop pack() hook, got " + inserted[0]);
        Path output = Path.of(args[1]); Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
        System.out.println("DESKTOP_UPDATE_BUTTON_INJECTION_PASS");
    }
}
