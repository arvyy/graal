/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.snippets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.graalvm.compiler.api.replacements.Fold;
import org.graalvm.compiler.api.replacements.Snippet;
import org.graalvm.compiler.api.replacements.Snippet.ConstantParameter;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.nodes.extended.MembarNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.ReplacementsUtil;
import org.graalvm.compiler.replacements.SnippetTemplate;
import org.graalvm.compiler.replacements.SnippetTemplate.Arguments;
import org.graalvm.compiler.replacements.SnippetTemplate.SnippetInfo;
import org.graalvm.compiler.replacements.Snippets;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.impl.InternalPlatform;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.graal.nodes.VerificationMarkerNode;
import com.oracle.svm.core.graal.stackvalue.LoweredStackValueNode;
import com.oracle.svm.core.graal.stackvalue.StackValueNode.StackSlotIdentity;
import com.oracle.svm.core.headers.LibC;
import com.oracle.svm.core.headers.WindowsAPIs;
import com.oracle.svm.core.nodes.CFunctionEpilogueNode;
import com.oracle.svm.core.nodes.CFunctionEpilogueNode.CapturableState;
import com.oracle.svm.core.nodes.CFunctionPrologueDataNode;
import com.oracle.svm.core.nodes.CFunctionPrologueNode;
import com.oracle.svm.core.nodes.CPrologueData;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.stack.JavaFrameAnchor;
import com.oracle.svm.core.stack.JavaFrameAnchors;
import com.oracle.svm.core.thread.Safepoint;
import com.oracle.svm.core.thread.VMThreads.StatusSupport;
import com.oracle.svm.core.util.VMError;

/**
 * Snippets for calling from Java to C. This is the inverse of {@link CEntryPointSnippets}.
 *
 * The {@link JavaFrameAnchor} has to be set up because the top of the stack will no longer be a
 * Java frame. In addition, the thread state needs to transition from being in
 * {@link StatusSupport#STATUS_IN_JAVA Java} state to being in {@link StatusSupport#STATUS_IN_NATIVE
 * Native} state on the way in, and to transition the thread state from Native state to Java state
 * on the way out.
 *
 * Among the complications is that the C function may try to return while a safepoint is in
 * progress, i.e., the thread state is not Native but {@link StatusSupport#STATUS_IN_SAFEPOINT
 * Safepoint}. It must not be allowed back into Java code until the safepoint is finished.
 *
 * Only parts of these semantics can be implemented via snippets: The low-level code to initialize
 * the {@link JavaFrameAnchor} and to transition the thread from Java state to Native state must
 * only be done immediately before the call, because an accurate pointer map is necessary for the
 * last instruction pointer stored in the {@link JavaFrameAnchor}. Therefore, the
 * {@link JavaFrameAnchor} is filled at the lowest possible level: during code generation as part of
 * the same LIR operation that emits the call to the C function. Using the same LIR instruction is
 * the only way to ensure that neither the instruction scheduler nor the register allocator emit any
 * instructions between the capture of the instruction pointer and the actual call instruction.
 */
public final class CFunctionSnippets extends SubstrateTemplates implements Snippets {

    private final SnippetInfo prologue;
    private final SnippetInfo epilogue;

    /**
     * A unique object that identifies the frame anchor stack value. Multiple C function calls
     * inlined into the same Java method share the stack slots for the frame anchor.
     */
    private static final StackSlotIdentity frameAnchorIdentity = new StackSlotIdentity("CFunctionSnippets.frameAnchorIdentifier", true);

    @Snippet
    private static CPrologueData prologueSnippet(@ConstantParameter int newThreadStatus) {
        if (newThreadStatus != StatusSupport.STATUS_ILLEGAL) {
            /* Push a JavaFrameAnchor to the thread-local linked list. */
            JavaFrameAnchor anchor = (JavaFrameAnchor) LoweredStackValueNode.loweredStackValue(SizeOf.get(JavaFrameAnchor.class), FrameAccess.wordSize(), frameAnchorIdentity);
            JavaFrameAnchors.pushFrameAnchor(anchor);

            /*
             * The content of the new anchor is uninitialized at this point. It is filled as late as
             * possible, immediately before the C call instruction, so that the pointer map for the
             * last instruction pointer matches the pointer map of the C call. The thread state
             * transition into Native state also happens immediately before the C call.
             */

            return CFunctionPrologueDataNode.cFunctionPrologueData(anchor, newThreadStatus);
        } else {
            return null;
        }
    }

    @Uninterruptible(reason = "Interruptions might change call state.")
    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    public static void captureCallState(int statesToCapture, CIntPointer captureBuffer) {
        /*
         * This method is called from inside the CFunction prologue before transitioning back into
         * Java. This means that the calls we do here should not transition to/from native, as this
         * would introduce a safepoint.
         *
         * Note that the states must be captured in the same order as in the JDK: GET_LAST_ERROR,
         * WSA_GET_LAST_ERROR, ERRNO
         *
         * There doesn't seem to be a "best" behavior when capture is requested for a state which is
         * not supported (e.g. GET_LAST_ERROR on Linux). We simply ignore such capture requests and
         * push the responsibility of checking that this won't happen to the caller (similarly to
         * what is done in DowncallLinker::capture_state in HotSpot).
         *
         * Finally, in order for this implementation to correctly mimic the JDK's behavior, the
         * following assumptions are made: 1. WindowsAPI is supported <=> the OS is windows 2. LibC
         * is always supported
         */
        int i = 0;
        if (WindowsAPIs.isSupported()) {
            if ((statesToCapture & CapturableState.GET_LAST_ERROR.mask()) != 0) {
                captureBuffer.write(i, WindowsAPIs.getLastError());
            }
            ++i;
            if ((statesToCapture & CapturableState.WSA_GET_LAST_ERROR.mask()) != 0) {
                captureBuffer.write(i, WindowsAPIs.wsaGetLastError());
            }
            ++i;
        }
        if (LibC.isSupported()) {
            if ((statesToCapture & CapturableState.ERRNO.mask()) != 0) {
                captureBuffer.write(i, LibC.errno());
            }
            ++i;
        }
    }

    private static final SnippetRuntime.SubstrateForeignCallDescriptor CAPTURE_CALL_STATE = SnippetRuntime.findForeignCall(CFunctionSnippets.class, "captureCallState", false, LocationIdentity.any());

    @Node.NodeIntrinsic(value = ForeignCallNode.class)
    public static native void callCaptureCallState(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int states, CIntPointer captureBuffer);

    @Fold
    static boolean checkIfCaptureNeeded(int states) {
        return states != 0;
    }

    @Snippet
    private static void epilogueSnippet(@ConstantParameter int oldThreadStatus, @ConstantParameter int statesToCapture, CIntPointer captureBuffer) {
        /*
         * Putting this trivial check in a separate function might be a bit overkill, but this
         * ensures that it is correctly folded, which should be sufficient to ensure that the
         * if-the-else is itself folded.
         */
        if (checkIfCaptureNeeded(statesToCapture)) {
            callCaptureCallState(CAPTURE_CALL_STATE, statesToCapture, captureBuffer);
        }

        if (oldThreadStatus != StatusSupport.STATUS_ILLEGAL) {
            if (SubstrateOptions.MultiThreaded.getValue()) {
                if (oldThreadStatus == StatusSupport.STATUS_IN_NATIVE) {
                    Safepoint.transitionNativeToJava(true);
                } else if (oldThreadStatus == StatusSupport.STATUS_IN_VM) {
                    Safepoint.transitionVMToJava(true);
                } else {
                    ReplacementsUtil.staticAssert(false, "Unexpected thread status");
                }
            } else {
                JavaFrameAnchors.popFrameAnchor();
            }

            /*
             * Ensure that no floating reads are scheduled before we are done with the transition.
             * All memory dependencies of the replaced CEntryPointEpilogueNode are re-wired to this
             * KillMemoryNode since this is the last kill-all node of the snippet.
             */
            MembarNode.memoryBarrier(MembarNode.FenceKind.NONE, LocationIdentity.ANY_LOCATION);
        }
    }

    CFunctionSnippets(OptionValues options, Providers providers, Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        super(options, providers);

        this.prologue = snippet(providers, CFunctionSnippets.class, "prologueSnippet");
        this.epilogue = snippet(providers, CFunctionSnippets.class, "epilogueSnippet");

        lowerings.put(CFunctionPrologueNode.class, new CFunctionPrologueLowering());
        lowerings.put(CFunctionEpilogueNode.class, new CFunctionEpilogueLowering());
    }

    class CFunctionPrologueLowering implements NodeLoweringProvider<CFunctionPrologueNode> {

        @Override
        public void lower(CFunctionPrologueNode node, LoweringTool tool) {
            if (tool.getLoweringStage() != LoweringTool.StandardLoweringStage.LOW_TIER) {
                return;
            }
            matchCallStructure(node);

            /*
             * Mark the begin (and in the epilogueSnippet the end) of the C function transition.
             * Before code generation, we need to verify that the pointer maps of all call
             * instructions (the actual C function call and the slow-path call for the
             * Native-to-Java transition have the same pointer map.
             */
            node.graph().addBeforeFixed(node, node.graph().add(new VerificationMarkerNode(node.getMarker())));

            int newThreadStatus = node.getNewThreadStatus();

            Arguments args = new Arguments(prologue, node.graph().getGuardsStage(), tool.getLoweringStage());
            args.addConst("newThreadStatus", newThreadStatus);
            SnippetTemplate template = template(tool, node, args);
            template.setMayRemoveLocation(true);
            template.instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }

    class CFunctionEpilogueLowering implements NodeLoweringProvider<CFunctionEpilogueNode> {

        @Override
        public void lower(CFunctionEpilogueNode node, LoweringTool tool) {
            if (tool.getLoweringStage() != LoweringTool.StandardLoweringStage.LOW_TIER) {
                return;
            }
            node.graph().addAfterFixed(node, node.graph().add(new VerificationMarkerNode(node.getMarker())));

            int oldThreadStatus = node.getOldThreadStatus();

            Arguments args = new Arguments(epilogue, node.graph().getGuardsStage(), tool.getLoweringStage());
            args.addConst("oldThreadStatus", oldThreadStatus);
            args.addConst("statesToCapture", CapturableState.mask(node.getStatesToCapture()), StampFactory.objectNonNull());
            ValueNode buffer = node.getCaptureBuffer();
            if (buffer == null) {
                // Set it to the null pointer
                buffer = ConstantNode.forLong(0, node.graph());
            }
            args.add("captureBuffer", buffer);

            SnippetTemplate template = template(tool, node, args);
            template.setMayRemoveLocation(true);
            template.instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }

    /**
     * Verify the correct structure of C function calls: A {@link CFunctionPrologueNode}, a
     * {@link InvokeNode}, and a {@link CFunctionEpilogueNode} must be in the same block.
     *
     * For later verification purposes, we match the unique marker objects of the prologue/epilogue
     * sequence.
     */
    private static void matchCallStructure(CFunctionPrologueNode prologueNode) {
        FixedNode cur = prologueNode;
        FixedNode singleInvoke = null;
        List<Node> seenNodes = new ArrayList<>();
        while (true) {
            seenNodes.add(cur);
            if (cur instanceof Invoke) {
                if (singleInvoke != null) {
                    throw VMError.shouldNotReachHere("Found more than one invoke: " + seenNodes);
                } else if (cur instanceof InvokeWithExceptionNode) {
                    throw VMError.shouldNotReachHere("Found InvokeWithExceptionNode: " + cur + " in " + seenNodes);
                }
                InvokeNode invoke = (InvokeNode) cur;

                if (prologueNode.getNewThreadStatus() != StatusSupport.STATUS_ILLEGAL) {
                    /*
                     * We are re-using the classInit field of the InvokeNode to store the
                     * CFunctionPrologueNode. During lowering, we create a PrologueDataNode that
                     * holds all the prologue-related data that the invoke needs in the backend.
                     *
                     * The classInit field is in every InvokeNode, and it is otherwise unused by
                     * Substrate VM (it is used only by the Java HotSpot VM). If we ever need the
                     * classInit field for other purposes, we need to create a new subclass of
                     * InvokeNode, and replace the invoke here with an instance of that new
                     * subclass.
                     */
                    VMError.guarantee(invoke.classInit() == null, "Re-using the classInit field to store the JavaFrameAnchor");
                    invoke.setClassInit(prologueNode);
                }

                singleInvoke = cur;
            }

            if (cur instanceof CFunctionEpilogueNode) {
                /* Success: found a matching epilogue. */
                prologueNode.getMarker().setEpilogueMarker(((CFunctionEpilogueNode) cur).getMarker());
                return;
            }

            if (!(cur instanceof FixedWithNextNode)) {
                throw VMError.shouldNotReachHere("Did not find a matching CFunctionEpilogueNode in same block: " + seenNodes);
            }
            cur = ((FixedWithNextNode) cur).next();
        }
    }

    public static void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(CAPTURE_CALL_STATE);
    }
}

@AutomaticallyRegisteredFeature
@Platforms(InternalPlatform.NATIVE_ONLY.class)
class CFunctionSnippetsFeature implements InternalFeature {
    @Override
    @SuppressWarnings("unused")
    public void registerLowerings(RuntimeConfiguration runtimeConfig, OptionValues options, Providers providers,
                    Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings, boolean hosted) {
        new CFunctionSnippets(options, providers, lowerings);
    }

    @Override
    public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        CFunctionSnippets.registerForeignCalls(foreignCalls);
    }
}
