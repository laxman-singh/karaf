/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.karaf.gshell.console.jline;

import jline.*;
import org.osgi.service.command.CommandSession;
import org.osgi.service.command.Converter;
import org.osgi.service.command.CommandProcessor;
import org.apache.felix.karaf.gshell.console.Completer;
import org.fusesource.jansi.Ansi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Properties;

public class Console implements Runnable
{

    public static final String PROMPT = "PROMPT";
    public static final String DEFAULT_PROMPT =
             "\"\u001B\\[1m${USER}\u001B\\[0m@${APPLICATION}> \"";

    private CommandSession session;
    private ConsoleReader reader;
    private BlockingQueue<Integer> queue = new ArrayBlockingQueue<Integer>(1024);
    private boolean interrupt;
    private Thread pipe;
    private boolean running;
    private Runnable closeCallback;
    private Terminal terminal;
    private InputStream consoleInput;
    private InputStream in;
    private PrintStream out;
    private PrintStream err;

    public Console(CommandProcessor processor,
                   InputStream in,
                   PrintStream out,
                   PrintStream err,
                   Terminal term,
                   Completer completer,
                   Runnable closeCallback) throws Exception
    {
        this.in = in;
        this.out = out;
        this.err = err;
        this.consoleInput = new ConsoleInputStream();
        this.session = processor.createSession(this.consoleInput, this.out, this.err);
        this.terminal = term == null ? new UnsupportedTerminal() : term;
        this.closeCallback = closeCallback;
        reader = new ConsoleReader(this.consoleInput,
                                   new PrintWriter(this.out),
                                   getClass().getResourceAsStream("keybinding.properties"),
                                   this.terminal);
        if (completer != null) {
            reader.addCompletor(new CompleterAsCompletor(completer));
        }
        pipe = new Thread(new Pipe());
        pipe.setName("gogo shell pipe thread");
        pipe.setDaemon(true);
    }

    public CommandSession getSession() {
        return session;
    }

    public void close() {
        //System.err.println("Closing");
        running = false;
        pipe.interrupt();
        Thread.interrupted();
    }

    public void run()
    {
        running = true;
        pipe.start();
        welcome();
        while (running) {
            try {
                String line = reader.readLine(getPrompt());
                if (line == null)
                {
                    break;
                }
                //session.getConsole().println("Executing: " + line);
                Object result = session.execute(line);
                if (result != null)
                {
                    session.getConsole().println(session.format(result, Converter.INSPECT));
                }
            }
            catch (InterruptedIOException e)
            {
                //System.err.println("^C");
                // TODO: interrupt current thread
            }
            catch (Throwable t)
            {
                t.printStackTrace(session.getConsole());
                //System.err.println("Exception: " + t.getMessage());
            }
        }
        //System.err.println("Exiting console...");
        if (closeCallback != null)
        {
            closeCallback.run();
        }
    }

    protected void welcome() {
        Properties props = new Properties();
        loadProps(props, "/org/apache/felix/karaf/gshell/console/branding.properties");
        loadProps(props, "/org/apache/felix/karaf/branding/branding.properties");
        String welcome = props.getProperty("welcome");
        if (welcome != null && welcome.length() > 0) {
            session.getConsole().println(welcome);
        }
    }

    private void loadProps(Properties props, String resource) {
        InputStream is = null;
        try {
            is = getClass().getClassLoader().getResourceAsStream(resource);
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            // ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    protected String getPrompt() {
        try {
            String prompt;
            try {
                Object p = session.get(PROMPT);
                prompt = p != null ? p.toString() : DEFAULT_PROMPT;
            } catch (Throwable t) {
                prompt = DEFAULT_PROMPT;
            }
            Object v = session.execute(prompt);
            if (v != null) {
                prompt = v.toString();
            }
            return prompt;
        } catch (Throwable t) {
            return "$ ";
        }
    }

    private void checkInterrupt() throws IOException {
        if (interrupt) {
            interrupt = false;
            throw new InterruptedIOException("Keyboard interruption");
        }
    }

    private void interrupt() {
        interrupt = true;
    }

    private class ConsoleInputStream extends InputStream
    {
        @Override
        public int read() throws IOException
        {
            if (!running)
            {
                return -1;
            }
            checkInterrupt();
            int i;
            try {
                i = queue.take();
            }
            catch (InterruptedException e)
            {
                throw new InterruptedIOException();
            }
            if (i == 4)
            {
                return -1;
            }
            checkInterrupt();
            return i;
        }
    }

    private class Pipe implements Runnable
    {
        public void run()
        {
            try {
                while (running)
                {
                    try
                    {
                        int c = in.read();
                        if (c == -1 || c == 4)
                        {
                            //System.err.println("Received  " + c + " ... closing");
                            err.println("^D");
                            queue.put(c);
                            return;
                        }
                        else if (c == 3)
                        {
                            err.println("^C");
                            reader.getCursorBuffer().clearBuffer();
                            interrupt();
                            queue.put(c);
                        }
                        else
                        {
                            queue.put(c);
                        }
                    }
                    catch (Throwable t) {
                        //System.err.println("Exception in pipe: " + t);
                        return;
                    }
                }
            }
            finally
            {
                //System.err.println("Exiting pipe thread");
                close();
            }
        }
    }

}
