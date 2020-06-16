/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test;

import com.oracle.truffle.api.interop.TruffleException;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.test.polyglot.AbstractPolyglotTest;
import com.oracle.truffle.api.test.polyglot.ProxyLanguage;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AbstractTruffleExceptionTest extends AbstractPolyglotTest {

    private VerifyingHandler verifyingHandler;

    @Before
    public void setUp() {
        verifyingHandler = new VerifyingHandler();
    }

    @Test
    public void testLegacyCatchableException() {
        setupEnv(createContext(), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                LegacyCatchableException exception = new LegacyCatchableException();
                LangObject exceptionObject = new LangObject(exception);
                exception.setExceptionObject(exceptionObject);
                return createAST(languageInstance, exceptionObject, (node) -> exception.setLocation(node));
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY, BlockNode.Kind.CATCH, BlockNode.Kind.FINALLY);
        context.eval(ProxyLanguage.ID, "Test");
    }

    @Test
    public void testLegacyUnCatchableException() {
        setupEnv(createContext(), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                LegacyUnCatchableException exception = new LegacyUnCatchableException();
                LangObject exceptionObject = new LangObject(exception);
                exception.setExceptionObject(exceptionObject);
                return createAST(languageInstance, exceptionObject, (node) -> exception.setLocation(node));
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY);
        assertFails(() -> context.eval(ProxyLanguage.ID, "Test"), PolyglotException.class);
    }

    @Test
    public void testCatchableTruffleException() {
        setupEnv(createContext(), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                AbstractTruffleExceptionImpl exception = new AbstractTruffleExceptionImpl(true);
                return createAST(languageInstance, exception, (node) -> exception.setLocation(node));
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY, BlockNode.Kind.CATCH, BlockNode.Kind.FINALLY);
        context.eval(ProxyLanguage.ID, "Test");
    }

    @Test
    public void testUnCatchableTruffleException() {
        setupEnv(createContext(), new ProxyLanguage() {
            @Override
            protected CallTarget parse(TruffleLanguage.ParsingRequest request) throws Exception {
                AbstractTruffleExceptionImpl exception = new AbstractTruffleExceptionImpl(false);
                return createAST(languageInstance, exception, (node) -> exception.setLocation(node));
            }
        });
        verifyingHandler.expect(BlockNode.Kind.TRY);
        assertFails(() -> context.eval(ProxyLanguage.ID, "Test"), PolyglotException.class);
    }

    private Context createContext() {
        return Context.newBuilder().option(String.format("log.%s.level", verifyingHandler.loggerName), "FINE").logHandler(verifyingHandler).build();
    }

    private CallTarget createAST(TruffleLanguage<ProxyLanguage.LanguageContext> lang, Object exceptionObject,
                    Consumer<Node> throwNodeConsumer) {
        ThrowNode throwNode = new ThrowNode(exceptionObject);
        throwNodeConsumer.accept(throwNode);
        TryCatchNode tryCatch = new TryCatchNode(new BlockNode(BlockNode.Kind.TRY, throwNode),
                        new BlockNode(BlockNode.Kind.CATCH),
                        new BlockNode(BlockNode.Kind.FINALLY));
        return Truffle.getRuntime().createCallTarget(new TestRootNode(lang, tryCatch));
    }

    private static final class TestRootNode extends RootNode {

        @Child StatementNode body;

        TestRootNode(TruffleLanguage<?> language, StatementNode body) {
            super(language);
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return body.execute(frame);
        }
    }

    private abstract static class StatementNode extends Node {
        abstract Object execute(VirtualFrame frame);
    }

    private static class BlockNode extends StatementNode {

        enum Kind {
            TRY,
            CATCH,
            FINALLY
        }

        @Children private StatementNode[] children;

        BlockNode(Kind kind, StatementNode... children) {
            this.children = new StatementNode[children.length + 1];
            this.children[0] = new LogNode(kind.name());
            System.arraycopy(children, 0, this.children, 1, children.length);
        }

        @Override
        @ExplodeLoop
        Object execute(VirtualFrame frame) {
            Object res = null;
            for (StatementNode child : children) {
                res = child.execute(frame);
            }
            return res;
        }
    }

    private static class LogNode extends StatementNode {

        private static final TruffleLogger LOG = TruffleLogger.getLogger(ProxyLanguage.ID, AbstractTruffleExceptionTest.class);
        private final String message;

        LogNode(String message) {
            this.message = message;
        }

        @Override
        Object execute(VirtualFrame frame) {
            LOG.fine(message);
            return true;
        }
    }

    private static class TryCatchNode extends StatementNode {

        @Child private BlockNode block;
        @Child private BlockNode catchBlock;
        @Child private BlockNode finalizerBlock;
        @Child private InteropLibrary interop = InteropLibrary.getFactory().createDispatched(5);

        TryCatchNode(BlockNode block, BlockNode catchBlock, BlockNode finalizerBlock) {
            this.block = block;
            this.catchBlock = catchBlock;
            this.finalizerBlock = finalizerBlock;
        }

        @Override
        Object execute(VirtualFrame frame) {
            boolean runFinalization = true;
            try {
                return block.execute(frame);
            } catch (Throwable ex) {
                if (interop.isException(ex)) {
                    try {
                        Assert.assertEquals(TruffleException.Kind.GUEST_LANGUAGE_ERROR, interop.getExceptionKind(ex));
                        Assert.assertEquals(0, interop.getExceptionExitStatus(ex));
                        runFinalization = interop.isExceptionCatchable(ex);
                        if (runFinalization && catchBlock != null) {
                            return catchBlock.execute(frame);
                        } else {
                            interop.throwException(ex);
                        }
                    } catch (UnsupportedMessageException ume) {
                        ex.addSuppressed(ume);
                    }
                }
                throw ex;
            } finally {
                if (runFinalization && finalizerBlock != null) {
                    finalizerBlock.execute(frame);
                }
            }
        }
    }

    private static class ThrowNode extends StatementNode {

        private final Object exceptionObject;
        @Child InteropLibrary interop;

        public ThrowNode(Object exceptionObject) {
            this.exceptionObject = exceptionObject;
            interop = InteropLibrary.getFactory().create(exceptionObject);
        }

        @Override
        Object execute(VirtualFrame frame) {
            try {
                throw interop.throwException(exceptionObject);
            } catch (UnsupportedMessageException um) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new AssertionError("Unsupported message", um);
            }
        }
    }

    @SuppressWarnings({"serial", "deprecation"})
    static final class LegacyCatchableException extends RuntimeException implements com.oracle.truffle.api.TruffleException, TruffleObject {

        private Node location;
        private Object exeptionObject;

        LegacyCatchableException() {
        }

        void setExceptionObject(Object exeptionObject) {
            this.exeptionObject = exeptionObject;
        }

        void setLocation(Node node) {
            this.location = node;
        }

        @Override
        public Node getLocation() {
            return location;
        }

        @Override
        public Object getExceptionObject() {
            return exeptionObject;
        }
    }

    @SuppressWarnings({"serial", "deprecation"})
    static final class LegacyUnCatchableException extends ThreadDeath implements com.oracle.truffle.api.TruffleException, TruffleObject {

        private Node location;
        private Object exeptionObject;

        LegacyUnCatchableException() {
        }

        void setExceptionObject(Object exeptionObject) {
            this.exeptionObject = exeptionObject;
        }

        void setLocation(Node node) {
            this.location = node;
        }

        @Override
        public Node getLocation() {
            return location;
        }

        @Override
        public Object getExceptionObject() {
            return exeptionObject;
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class LangObject implements TruffleObject {

        private Throwable exception;

        LangObject(Throwable exception) {
            this.exception = exception;
        }

        @ExportMessage
        public boolean isException() {
            return exception != null;
        }

        @ExportMessage
        public boolean isExceptionCatchable() throws UnsupportedMessageException {
            if (exception == null) {
                throw UnsupportedMessageException.create();
            }
            return false;
        }

        @ExportMessage
        public RuntimeException throwException() throws UnsupportedMessageException {
            if (exception == null) {
                throw UnsupportedMessageException.create();
            }
            throw sthrow(RuntimeException.class, exception);
        }

        @SuppressWarnings({"unchecked", "unused"})
        private static <T extends Throwable> T sthrow(Class<T> type, Throwable t) throws T {
            throw (T) t;
        }
    }

    @SuppressWarnings("serial")
    static final class AbstractTruffleExceptionImpl extends TruffleException {

        private final boolean catchable;
        private Node location;

        AbstractTruffleExceptionImpl(boolean catchable) {
            this.catchable = catchable;
        }

        @Override
        public Node getLocation() {
            return location;
        }

        void setLocation(Node node) {
            this.location = node;
        }
    }

    private static final class VerifyingHandler extends Handler {

        final String loggerName = String.format("%s.%s", ProxyLanguage.ID, AbstractTruffleExceptionTest.class.getName());
        private Queue<String> expected = new ArrayDeque<>();

        void expect(BlockNode.Kind... kinds) {
            Arrays.stream(kinds).map(BlockNode.Kind::name).forEach(expected::add);
        }

        @Override
        public void publish(LogRecord lr) {
            if (loggerName.equals(lr.getLoggerName())) {
                String head = expected.remove();
                Assert.assertEquals(head, lr.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    }

}
