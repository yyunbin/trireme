/**
 * Copyright 2013 Apigee Corporation.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.modules;

import io.apigee.trireme.core.NodeRuntime;
import io.apigee.trireme.core.InternalNodeModule;
import io.apigee.trireme.core.Utils;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.annotations.JSFunction;
import org.mozilla.javascript.annotations.JSGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.apigee.trireme.core.ArgUtils.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;

/**
 * This class plugs in to an instance of native_stream_writable. It actually
 */

public class NativeOutputStreamAdapter
    implements InternalNodeModule
{
    public static final String MODULE_NAME = "native_output_stream";
    public static final String WRITABLE_MODULE_NAME = "native_stream_writable";

    /**
     * We don't know the actual window size in Java, so guess:
     */
    public static final int DEFAULT_WINDOW_COLS = 80;
    public static final int DEFAULT_WINDOW_ROWS = 24;

    protected static final Logger log = LoggerFactory.getLogger(NativeOutputAdapterImpl.class);

    @Override
    public String getModuleName()
    {
        return MODULE_NAME;
    }

    @Override
    public Scriptable registerExports(Context cx, Scriptable scope, NodeRuntime runner)
        throws InvocationTargetException, IllegalAccessException, InstantiationException
    {
        ScriptableObject.defineClass(scope, NativeOutputAdapterImpl.class);
        Scriptable exp = cx.newObject(scope);
        return exp;
    }

    /**
     * Create a native JavaScript object that implements the native_stream_writable module's interface,
     * and writes to the specified OutputStream using an instance of the adapter defined here.
     * This object may be used directly to support process.stdout and elsewhere.
     */
    public static Scriptable createNativeStream(Context cx, Scriptable scope, NodeRuntime runner,
                                                OutputStream out, boolean noClose, boolean couldBeTty)
    {
        Function ctor = (Function)runner.require(WRITABLE_MODULE_NAME, cx);

        NativeOutputAdapterImpl adapter =
            (NativeOutputAdapterImpl)cx.newObject(scope, NativeOutputAdapterImpl.CLASS_NAME);
        adapter.initialize(out, noClose, couldBeTty);

        Scriptable stream =
            (Scriptable)ctor.call(cx, scope, null,
                                  new Object[] { Context.getUndefinedValue(), adapter });
        return stream;
    }

    public static class NativeOutputAdapterImpl
        extends ScriptableObject
    {
        public static final String CLASS_NAME = "_nativeOutputStreamAdapter";

        private OutputStream out;
        private boolean noClose;
        private boolean isTty;

        @Override
        public String getClassName()
        {
            return CLASS_NAME;
        }

        public void initialize(OutputStream out, boolean noClose, boolean couldBeTty)
        {
            this.out = out;
            this.noClose = noClose;
            this.isTty = (couldBeTty && (System.console() != null));
        }

        @JSGetter("isTTY")
        @SuppressWarnings("unused")
        public boolean isTty() {
            return isTty;
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void write(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeOutputAdapterImpl self = (NativeOutputAdapterImpl)thisObj;
            ensureArg(args, 0);
            Scriptable chunk = ensureScriptable(args[0]);
            Function callback = null;

            if ((args.length > 1) && !Context.getUndefinedValue().equals(args[1])) {
                callback = functionArg(args, 1, false);
            }

            Buffer.BufferImpl buf;
            try {
                buf = (Buffer.BufferImpl)chunk;
            } catch (ClassCastException cce) {
                throw new EvaluatorException("Not a buffer");
            }

            if (log.isTraceEnabled()) {
                log.debug("Writing {} bytes to {}", buf.getLength(), self.out);
            }

            try {
                self.out.write(buf.getArray(), buf.getArrayOffset(), buf.getLength());
                if (callback != null) {
                    callback.call(cx, thisObj, thisObj,
                                  new Object[] {});
                }
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug("I/O error on write: {}", ioe);
                }
                if (callback != null) {
                    callback.call(cx, thisObj, thisObj,
                                  new Object[] { Utils.makeErrorObject(cx, thisObj, ioe.toString(), Constants.EIO) });
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void close(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            NativeOutputAdapterImpl self = (NativeOutputAdapterImpl)thisObj;
            if (!self.noClose) {
                log.debug("Closing output stream {}", self.out);
                try {
                    self.out.close();
                } catch (IOException ioe) {
                    log.debug("Error closing output: {}", ioe);
                }
            }
        }

        @JSFunction
        @SuppressWarnings("unused")
        public static void getWindowSize(Context cx, Scriptable thisObj, Object[] args, Function func)
        {
            Scriptable s = objArg(args, 0, Scriptable.class, true);

            int columns = DEFAULT_WINDOW_COLS;
            String cols = System.getenv("COLUMNS");
            if (cols != null) {
                try {
                    columns = Integer.parseInt(cols);
                } catch (NumberFormatException ignore) {
                }
            }
            s.put(0, s, columns);

            int rows = DEFAULT_WINDOW_ROWS;
            String rowStr = System.getenv("LINES");
            if (rowStr != null) {
                try {
                    rows = Integer.parseInt(rowStr);
                } catch (NumberFormatException ignore) {
                }
            }
            s.put(0, s, rows);
        }
    }
}
