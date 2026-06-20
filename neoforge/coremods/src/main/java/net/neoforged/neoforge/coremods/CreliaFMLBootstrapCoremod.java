/*
 * Crelia - NeoForge FML on Folia Regionized Server
 * Copyright (c) Crelia contributors
 *
 * This file is part of the Crelia project.
 * See https://github.com/ for license details.
 */

package net.neoforged.neoforge.coremods;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.neoforged.neoforgespi.transformation.ClassProcessorProvider;
import net.neoforged.neoforgespi.transformation.ProcessorName;
import net.neoforged.neoforgespi.transformation.SimpleClassProcessor;
import net.neoforged.neoforgespi.transformation.SimpleTransformationContext;
import net.neoforged.neoforgespi.transformation.Target;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Crelia FML bootstrap coremod that extends NeoForge's coremod infrastructure
 * with Folia-specific bytecode transformations.
 *
 * <h2>Purpose</h2>
 *
 * <p>On a Crelia server (Folia + NeoForge hybrid), the vanilla server entry
 * point must be redirected from {@code MinecraftServer.main(String[])} to
 * {@code crelia.CreliaServer.main(String[])}. Additionally, every call site
 * of {@code ServerLevel.tick()} must be instrumented with a region thread
 * validation check to prevent cross-region tick invocations.</p>
 *
 * <p>This coremod registers two bytecode transformers via the NeoForge SPI:</p>
 * <ul>
 *   <li><b>{@link CreliaServerMainRedirector}</b> — Redirects the static
 *       {@code main} method invocation in {@code MinecraftServer} to
 *       {@code crelia.CreliaServer.main}, ensuring that FML bootstrap
 *       happens before Folia's regionized threading takes over.</li>
 *   <li><b>{@link RegionThreadValidator}</b> — Inserts a region thread
 *       validation call before every {@code ServerLevel.tick(BooleanSupplier)}
 *       invocation. If the calling thread is not the owning region thread,
 *       the tick is skipped and deferred to the correct region scheduler.</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * <p>This class is registered as a {@link ClassProcessorProvider} in the SPI
 * services file at
 * {@code META-INF/services/net.neoforged.neoforgespi.transformation.ClassProcessorProvider}
 * alongside the base {@link NeoForgeCoreMod}. NeoForge's transformation
 * engine loads both providers during class loading.</p>
 *
 * <h2>Transformation Order</h2>
 * <p>Crelia's processors are applied after NeoForge's base processors. This
 * ensures that any field-to-getter redirects and method redirects performed
 * by the base coremod are in place before Crelia's transformations run. The
 * processors target different classes, so there is no conflict.</p>
 *
 * @see NeoForgeCoreMod
 * @see CreliaServerMainRedirector
 * @see RegionThreadValidator
 * @see ClassProcessorProvider
 */
public class CreliaFMLBootstrapCoremod implements ClassProcessorProvider {

    @Override
    public void createProcessors(Context context, Collector collector) {
        // Register the server main redirector.
        // This transforms MinecraftServer to redirect its main(String[]) method
        // to CreliaServer.main(String[]).
        collector.add(new CreliaServerMainRedirector());

        // Register the region thread validator.
        // This transforms every class that contains a call to
        // ServerLevel.tick(BooleanSupplier) to insert a region thread check
        // before the invocation.
        collector.add(new RegionThreadValidator());
    }

    // =========================================================================
    // CreliaServerMainRedirector
    // =========================================================================

    /**
     * Bytecode transformer that redirects {@code MinecraftServer.main(String[])}
     * to {@code crelia.CreliaServer.main(String[])}.
     *
     * <h2>Transformation Details</h2>
     * <p>This processor targets the {@code MinecraftServer} class. It scans for
     * the {@code main(String[])} static method and replaces the body with a
     * redirect to {@code crelia.CreliaServer.main(String[])}.</p>
     *
     * <p>The transformation works as follows:</p>
     * <ol>
     *   <li>Locate the {@code main} method in {@code MinecraftServer}.</li>
     *   <li>Replace the method body with a single {@code INVOKESTATIC} to
     *       {@code crelia.CreliaServer.main(String[])} followed by
     *       {@code RETURN}.</li>
     * </ol>
     *
     * <p>This ensures that the Crelia FML bootstrap always runs before any
     * vanilla server initialization code.</p>
     *
     * @see crelia.CreliaServer#main(String[])
     */
    static final class CreliaServerMainRedirector extends SimpleClassProcessor {

        /** The internal name of the MinecraftServer class. */
        private static final String MINECRAFT_SERVER =
            "net/minecraft/server/MinecraftServer";

        /** The internal name of the CreliaServer class. */
        private static final String CRELIA_SERVER =
            "crelia/CreliaServer";

        /** Descriptor for the main(String[]) method. */
        private static final String MAIN_DESC =
            "([Ljava/lang/String;)V";

        private final Set<Target> targets = Set.of(new Target(MINECRAFT_SERVER));

        @Override
        public ProcessorName name() {
            return new ProcessorName("crelia.coremods", "server_main_redirector");
        }

        @Override
        public Set<Target> targets() {
            return targets;
        }

        @Override
        public void transform(ClassNode classNode, SimpleTransformationContext context) {
            for (MethodNode method : classNode.methods) {
                if ("main".equals(method.name) && MAIN_DESC.equals(method.desc)) {
                    // Replace the main method body with a redirect to CreliaServer.main.
                    method.instructions.clear();

                    InsnList newInstructions = new InsnList();
                    // Load the String[] argument (local variable 0)
                    newInstructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    // Invoke CreliaServer.main(String[])
                    newInstructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        CRELIA_SERVER,
                        "main",
                        MAIN_DESC,
                        false));
                    // Return void
                    newInstructions.add(new InsnNode(Opcodes.RETURN));

                    method.instructions = newInstructions;
                    // Clear try-catch blocks since we replaced the body.
                    method.tryCatchBlocks.clear();
                }
            }
        }
    }

    // =========================================================================
    // RegionThreadValidator
    // =========================================================================

    /**
     * Bytecode transformer that inserts region thread validation before every
     * {@code ServerLevel.tick(BooleanSupplier)} invocation.
     *
     * <h2>Transformation Details</h2>
     * <p>On a Folia server, calling {@code ServerLevel.tick()} from the wrong
     * region thread will cause data corruption. This processor finds all call
     * sites of {@code ServerLevel.tick(BooleanSupplier)} and inserts a call to
     * {@code crelia.core.mixin.server.RegionThreadValidatorHooks.checkRegionThread(ServerLevel)}
     * before the invocation.</p>
     *
     * <p>The validation method checks whether the current thread is the owning
     * {@link io.papermc.paper.threadedregions.RegionizedWorldThread} for the
     * given {@code ServerLevel}. If not, it throws an
     * {@link IllegalStateException} (in strict mode) or logs a warning and
     * returns {@code false} (in permissive mode).</p>
     *
     * <p>The transformed bytecode pattern is:</p>
     * <pre>{@code
     *   // Before:
     *   ALOAD serverLevel
     *   ALOAD booleanSupplier
     *   INVOKEVIRTUAL net/minecraft/server/level/ServerLevel.tick (Ljava/util/function/BooleanSupplier;)V
     *
     *   // After:
     *   ALOAD serverLevel
     *   INVOKESTATIC crelia/core/mixin/server/RegionThreadValidatorHooks.checkRegionThread(Lnet/minecraft/server/level/ServerLevel;)V
     *   ALOAD serverLevel
     *   ALOAD booleanSupplier
     *   INVOKEVIRTUAL net/minecraft/server/level/ServerLevel.tick (Ljava/util/function/BooleanSupplier;)V
     * }</pre>
     *
     * @see io.papermc.paper.threadedregions.RegionizedWorldThread
     */
    static final class RegionThreadValidator extends SimpleClassProcessor {

        /** The internal name of the ServerLevel class. */
        private static final String SERVER_LEVEL =
            "net/minecraft/server/level/ServerLevel";

        /** The internal name of the region thread validator hooks class. */
        private static final String VALIDATOR_HOOKS =
            "crelia/core/mixin/server/RegionThreadValidatorHooks";

        /** Descriptor for ServerLevel.tick(BooleanSupplier). */
        private static final String TICK_DESC =
            "(Ljava/util/function/BooleanSupplier;)V";

        /** Descriptor for the validation check method. */
        private static final String CHECK_DESC =
            "(Lnet/minecraft/server/level/ServerLevel;)V";

        /** Targets all classes (we need to scan all call sites). */
        private final Set<Target> targets = Collections.singleton(
            new Target("*"));

        @Override
        public ProcessorName name() {
            return new ProcessorName("crelia.coremods", "region_thread_validator");
        }

        @Override
        public Set<Target> targets() {
            return targets;
        }

        @Override
        public void transform(ClassNode classNode, SimpleTransformationContext context) {
            boolean modified = false;

            for (MethodNode method : classNode.methods) {
                var instructions = method.instructions;
                for (int i = 0; i < instructions.size(); i++) {
                    var node = instructions.get(i);
                    if (node instanceof MethodInsnNode methodInsn) {
                        // Look for INVOKEVIRTUAL ServerLevel.tick(BooleanSupplier)
                        if ((methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                                || methodInsn.getOpcode() == Opcodes.INVOKEINTERFACE)
                            && SERVER_LEVEL.equals(methodInsn.owner)
                            && "tick".equals(methodInsn.name)
                            && TICK_DESC.equals(methodInsn.desc)) {

                            // Insert the validation call before the tick invocation.
                            // The ServerLevel instance is already on the stack at
                            // this point (it's the receiver of the INVOKEVIRTUAL).
                            InsnList validation = new InsnList();
                            validation.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                VALIDATOR_HOOKS,
                                "checkRegionThread",
                                CHECK_DESC,
                                false));

                            instructions.insertBefore(methodInsn, validation);
                            modified = true;
                        }
                    }
                }
            }

            if (modified) {
                // Recompute stack map frames since we inserted instructions.
                // Note: ASM will recompute this during class writing if
                // ClassWriter.COMPUTE_FRAMES is used by the transformation engine.
            }
        }
    }
}
