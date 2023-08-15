/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.operation;

import java.util.List;
import java.util.Set;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.BytecodeOSRNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.operation.introspection.ExceptionHandler;
import com.oracle.truffle.api.operation.introspection.Instruction;
import com.oracle.truffle.api.operation.introspection.OperationIntrospection;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base interface to be implemented by the root node of an Operations interpreter. Such a root node
 * should extend {@link com.oracle.truffle.api.nodes.RootNode} and be annotated with
 * {@link GenerateOperations @GenerateOperations}.
 *
 * @see GenerateOperations
 */
public interface OperationRootNode extends BytecodeOSRNode, OperationIntrospection.Provider {

    /**
     * Entrypoint to the root node.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param frame the frame used for execution
     * @return the value returned by the root node
     */
    Object execute(VirtualFrame frame);

    /**
     * Optional hook invoked before executing the root node.
     *
     * @param frame the frame used for execution
     */
    @SuppressWarnings("unused")
    default void executeProlog(VirtualFrame frame) {
    }

    /**
     * Optional hook invoked before leaving the root node.
     *
     * @param frame the frame used for execution
     * @param returnValue the value returned by the root node ({@code null} if an exception was
     *            thrown)
     * @param throwable the exception thrown by the root node ({@code null} if the root node
     *            returned normally)
     */
    @SuppressWarnings("unused")
    default void executeEpilog(VirtualFrame frame, Object returnValue, Throwable throwable) {
    }

    /**
     * Optional hook invoked when an internal exception (i.e., anything other than
     * {@link AbstractTruffleException}) is thrown during execution. This hook can be used to
     * convert such exceptions into guest-language exceptions that can be handled by guest code.
     *
     * <p>
     * For example, if a Java {@link StackOverflowError} is thrown, this hook can be used to return
     * a guest-language equivalent exception that the guest code understands.
     *
     * <p>
     * If the return value is an {@link AbstractTruffleException}, it will be forwarded to the guest
     * code for handling. The exception will also be intercepted by
     * {@link #interceptTruffleException}.
     *
     * If the return value is not an {@link AbstractTruffleException}, it will be rethrown. Thus, if
     * an internal error cannot be converted to a guest exception, it can simply be returned.
     *
     * @param t the internal exception
     * @param bci the bytecode index of the instruction that caused the exception
     * @return an equivalent guest-language exception or an exception to be rethrown
     */
    @SuppressWarnings("unused")
    default Throwable interceptInternalException(Throwable t, int bci) {
        return t;
    }

    /**
     * Optional hook invoked when a Truffle exception is thrown during execution. This hook can be
     * used to preprocess the exception or replace it with another exception before it is handled.
     *
     * @param ex the Truffle exception
     * @param bci the bytecode index of the instruction that caused the exception
     * @return the Truffle exception to be handled by guest code
     */
    @SuppressWarnings("unused")
    default AbstractTruffleException interceptTruffleException(AbstractTruffleException ex, VirtualFrame frame, int bci) {
        return ex;
    }

    /**
     * Sets an invocation threshold that must be reached before the
     * {@link GenerateOperations#enableBaselineInterpreter baseline interpreter} switches to a
     * specializing interpreter. This method has no effect if there is no baseline interpreter or
     * the root node has node has already switched to a specializing interpreter.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param invocationCount the invocation threshold
     */
    @SuppressWarnings("unused")
    default void setBaselineInterpreterThreshold(int invocationCount) {
    }

    /**
     * Gets the {@link SourceSection} associated with a particular {@code bci}. Returns {@code null}
     * if the node was not parsed {@link OperationConfig#WITH_SOURCE with sources} or if there is no
     * associated source section for the given {@code bci}.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param bci the bytecode index
     * @return a source section corresponding to the bci, or {@code null} if no source section is
     *         available
     */
    @SuppressWarnings("unused")
    default SourceSection getSourceSectionAtBci(int bci) {
        throw new AbstractMethodError();
    }

    /**
     * Gets the {@code bci} associated with a particular
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @param frameInstance the frame instance
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    static int findBci(FrameInstance frameInstance) {
        /**
         * We use two strategies to communicate the current bci.
         *
         * For cached (non-baseline) interpreters, each operation node corresponds to a unique bci.
         * We can walk the parent chain of the call node to find the operation node, and then use it
         * to compute a bci. This incurs no overhead during regular execution.
         *
         * For baseline interpreters, we use uncached nodes, so the call node (if any) is not
         * adopted by an operation node. Instead, the baseline interpreter stores the current bci
         * into the frame before any operation that might call another node. This incurs a bit of
         * overhead during regular execution (but just for the baseline interpreter).
         */
        for (Node operationNode = frameInstance.getCallNode(); operationNode != null; operationNode = operationNode.getParent()) {
            if (operationNode.getParent() instanceof OperationRootNode rootNode) {
                return rootNode.findBciOfOperationNode(operationNode);
            }
        }
        if (frameInstance.getCallTarget() instanceof RootCallTarget rootCallTarget && rootCallTarget.getRootNode() instanceof OperationRootNode operationRootNode) {
            return operationRootNode.readBciFromFrame(frameInstance.getFrame(FrameAccess.READ_ONLY));
        }
        return -1;
    }

    /**
     * Gets the {@code bci} associated with a particular operation node.
     *
     * Note: this is a slow path operation that gets invoked by {@link OperationRootNode#findBci}.
     * It should not be called directly. Operation specializations can use {@code @Bind("$bci")} to
     * obtain the current bytecode index on the fast path.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param operationNode the operation node
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    @SuppressWarnings("unused")
    default int findBciOfOperationNode(Node operationNode) {
        throw new AbstractMethodError();
    }

    /**
     * Reads the {@code bci} stored in the frame.
     *
     * Note: this is a slow path operation that gets invoked by {@link OperationRootNode#findBci}.
     * It should not be called directly. Operation specializations can use {@code @Bind("$bci")} to
     * obtain the current bytecode index on the fast path.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param frame the frame obtained from a stack walk
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    @SuppressWarnings("unused")
    default int readBciFromFrame(Frame frame) {
        throw new AbstractMethodError();
    }

    /**
     * Returns a new array containing the current value of each local in the
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @see {@link #getLocals(Frame)}
     * @param frameInstance the frame instance
     * @return a new array of local values, or null if the frame instance does not correspond to an
     *         {@link OperationRootNode}
     */
    static Object[] getLocals(FrameInstance frameInstance) {
        if (!(frameInstance.getCallTarget() instanceof RootCallTarget rootCallTarget)) {
            return null;
        }
        if (rootCallTarget.getRootNode() instanceof OperationRootNode operationRootNode) {
            return operationRootNode.getLocals(frameInstance.getFrame(FrameAccess.READ_ONLY));
        } else if (rootCallTarget.getRootNode() instanceof ContinuationRootNode continuationRootNode) {
            return continuationRootNode.getLocals(frameInstance.getFrame(FrameAccess.READ_ONLY));
        }
        return null;
    }

    /**
     * Returns a new array containing the current value of each local in the frame. This method
     * should only be used for slow-path use-cases (like frame introspection). Prefer regular local
     * load operations (via {@code builder.emitLoadLocal(operationLocal}) when possible.
     *
     * An operation can use this method by binding the root node to a specialization parameter (via
     * {@code @Bind("$root")}) and then invoking the method on the root node.
     *
     * The order of the locals corresponds to the order in which they were created using
     * {@code createLocal()}. It is up to the language to track the creation order.
     *
     * This method will be generated by the Operation DSL. Do not override.
     *
     * @param frame the frame to read locals from
     * @return an array of local values
     */
    @SuppressWarnings("unused")
    default Object[] getLocals(Frame frame) {
        throw new AbstractMethodError();
    }

    @SuppressWarnings("unused")
    default InstrumentableNode materializeInstrumentTree(Set<Class<? extends Tag>> materializedTags) {
        throw new AbstractMethodError();
    }

    /**
     * If an {@code OperationRootNode} is not well-formed, the Operation DSL will provide an
     * actionable error message to fix it. The default implementations below are provided so that
     * "abstract method not implemented" errors do not hide the DSL's error messages. When there are
     * no errors, the DSL will generate actual implementations for these methods.
     */

    /**
     * Hook required to support on-stack-replacement.
     *
     * This method will be generated by the Operation DSL. Do not override.
     */
    @Override
    default Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
        throw new AbstractMethodError();
    }

    /**
     * Hook required to support on-stack-replacement.
     *
     * This method will be generated by the Operation DSL. Do not override.
     */
    @Override
    default void setOSRMetadata(Object osrMetadata) {
        throw new AbstractMethodError();
    }

    /**
     * Hook required to support on-stack-replacement.
     *
     * This method will be generated by the Operation DSL. Do not override.
     */
    @Override
    default Object getOSRMetadata() {
        throw new AbstractMethodError();
    }

    /**
     * Helper method to dump the root node's bytecode.
     *
     * @return a string representation of the bytecode
     */
    default String dump() {
        StringBuilder sb = new StringBuilder();
        OperationIntrospection id = getIntrospectionData();

        for (Instruction i : id.getInstructions()) {
            sb.append(i.toString()).append('\n');
        }

        List<ExceptionHandler> handlers = id.getExceptionHandlers();
        if (handlers.size() > 0) {
            sb.append("Exception handlers:\n");
            for (ExceptionHandler eh : handlers) {
                sb.append("  ").append(eh.toString()).append('\n');
            }
        }

        return sb.toString();
    }
}
