package com.oracle.truffle.api.operation.test;

import static org.junit.Assert.assertEquals;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.operation.GenerateOperations;
import com.oracle.truffle.api.operation.Operation;
import com.oracle.truffle.api.operation.OperationConfig;
import com.oracle.truffle.api.operation.OperationNodes;
import com.oracle.truffle.api.operation.OperationParser;
import com.oracle.truffle.api.operation.OperationRootNode;
import com.oracle.truffle.api.operation.test.OperationNodeWithHooks.ThrowStackOverflow;

public class OperationHookTest {

    public static OperationNodeWithHooks parseNode(OperationParser<OperationNodeWithHooksGen.Builder> builder) {
        OperationNodes<OperationNodeWithHooks> nodes = OperationNodeWithHooksGen.create(OperationConfig.DEFAULT, builder);
        return nodes.getNodes().get(0);
    }

    @Test
    public void testSimple() {
        OperationNodeWithHooks root = parseNode(b -> {
            b.beginRoot(null);
            b.beginReturn();
            b.emitReadArgument();
            b.endReturn();
            b.endRoot();
        });
        Object[] refs = new Object[2];
        root.setRefs(refs);

        assertEquals(42, root.getCallTarget().call(42));
        assertEquals(42, refs[0]);
        assertEquals(42, refs[1]);
    }

    @Test
    public void testThrow() {
        OperationNodeWithHooks root = parseNode(b -> {
            b.beginRoot(null);
            b.beginReturn();
            b.beginThrow();
            b.emitLoadConstant(123);
            b.endThrow();
            b.endReturn();
            b.endRoot();
        });
        Object[] refs = new Object[2];
        root.setRefs(refs);

        try {
            root.getCallTarget().call(42);
            Assert.fail("call should have thrown an exception");
        } catch (OperationNodeWithHooks.MyException ex) {
            assertEquals(123, ex.result);
        }

        assertEquals(42, refs[0]);
        assertEquals(123, refs[1]);
    }

    @Test
    public void testThrowStackOverflow() {
        OperationNodeWithHooks root = parseNode(b -> {
            b.beginRoot(null);
            b.beginReturn();
            b.emitThrowStackOverflow();
            b.endReturn();
            b.endRoot();
        });
        Object[] refs = new Object[2];
        root.setRefs(refs);

        try {
            root.getCallTarget().call(42);
            Assert.fail("call should have thrown an exception");
        } catch (OperationNodeWithHooks.MyException ex) {
            assertEquals(ThrowStackOverflow.MESSAGE, ex.result);
        }

        assertEquals(42, refs[0]);
        assertEquals(ThrowStackOverflow.MESSAGE, refs[1]);
    }
}

@GenerateOperations(languageClass = TestOperationsLanguage.class)
abstract class OperationNodeWithHooks extends RootNode implements OperationRootNode {
    // Used to validate whether hooks get called.
    private Object[] refs;

    protected OperationNodeWithHooks(TruffleLanguage<?> language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    void setRefs(Object[] refs) {
        assert refs.length == 2;
        this.refs = refs;
    }

    @Override
    public void executeProlog(VirtualFrame frame) {
        refs[0] = frame.getArguments()[0];
    }

    @Override
    public void executeEpilog(VirtualFrame frame, Object returnValue, Throwable throwable) {
        if (throwable != null) {
            if (throwable instanceof MyException myEx) {
                refs[1] = myEx.result;
            }
        } else {
            refs[1] = returnValue;
        }
    }

    @Override
    public Throwable interceptInternalException(Throwable t) {
        return new MyException(t.getMessage());
    }

    public static final class MyException extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;
        public final Object result;

        MyException(Object result) {
            super();
            this.result = result;
        }
    }

    @Operation
    public static final class ReadArgument {
        @Specialization
        public static Object perform(VirtualFrame frame) {
            return frame.getArguments()[0];
        }
    }

    @Operation
    public static final class Throw {
        @Specialization
        public static Object perform(Object result) {
            throw new MyException(result);
        }
    }

    @Operation
    public static final class ThrowStackOverflow {
        public static final String MESSAGE = "unbounded recursion";

        @Specialization
        public static Object perform() {
            throw new StackOverflowError(MESSAGE);
        }
    }
}