/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2017 jPOS Software SRL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.transaction.participant;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.HashMap;

import org.jdom2.Element;
import org.jpos.core.Configurable;
import org.jpos.core.XmlConfigurable;
import org.jpos.core.Configuration;
import org.jpos.core.ConfigurationException;
import org.jpos.transaction.TransactionParticipant;
import org.jpos.transaction.AbortParticipant;
import org.jpos.transaction.Context;
import org.jpos.transaction.TransactionManager;
import org.jpos.util.Log;

import org.jpos.groovy.GroovySetup;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;


/**
 * <p>A TransactionParticipant whose prepare, commit and abort methods can be
 * specified using Groovy scripts.
 *
 * <p>To indicate what code to execute for any of the TM life-cycle methods just add
 * an element named 'prepare', 'commit' or 'abort' and optionally 'prepare-for-abort'
 * contained in that of the participant.
 *
 * <p>The 'prepare' and 'prepare-for-abort' methods are expected to return an Integer object
 * with the TM standard result values (PREPARED, ABORTED, etc).
 *
 * <p>The Groovy script code can be placed as part of the element's content (a CDATA section
 * is recommended), or in an external file pointed to by the 'src' attribute. We also
 * recommend adding a "realm" attribute to identify errors in the logs, especially if you
 * have several instances of GroovyParticipant in your transaction manager.
 *
 * <p>The following variables will be bound to each Groovy script's {@code Binding}:
 * <ul>
 *  <li><b>id</b> - the transaction {@code int id} passed to the participant's method</li>
 *  <li><b>ctx</b> - the transaction {@code Serializable ctx} passed to the participant's method</li>
 *  <li><b>log</b> - a reference to {@code this} instance (since this class extends {@code org.jpos.util.Log})</li>
 *  <li><b>cfg</b> - this {@code TransactionParticipant}'s {@code Configuration} properties</li>
 *  <li><b>tm</b> - a reference to the {@code TransactionManager}'s executing this transaction</li>
 *
 * </ul>
 *
 * <p>By default, scripts are pre-compiled by a GroovyClassLoader. If you want the script
 * to be evaluated each time, then set the "compiled" property to "false".
 *
 * Add a transaction participant like this:
 *
 * <pre>
 *     &lt;participant class="org.jpos.transaction.participant.GroovyParticipant" logger="Q2" realm="groovy-test"&gt;
 *       &lt;prepare src="deploy/prepare.groovy" /&gt;
 *       &lt;commit src="deploy/commit.groovy" /&gt;
 *       &lt;abort&gt;
 *         &lt;![CDATA[
 *             // ... embedded script
 *         ]]&gt;
 *       &lt;/abort&gt;
 *     &lt;/participant&gt;
 * </pre>
 *
 * @author <a href="mailto:barspi@transactility.com">Barzilai Spinak</a>
 * @version $Revision$ $Date$
 */

@SuppressWarnings("unused")
public class GroovyParticipant extends Log
    implements TransactionParticipant, AbortParticipant, XmlConfigurable, Configurable
{
    private boolean compiled= true;
    private GroovyClassLoader gcl;

    // map script part (prepare, abort...) to a name which can be a path, a realm or something to identify in error logs
    private HashMap<String, String> scriptNames= new HashMap<>();

    // prepare, prepareForAbort, commit, and abort
    // can be instances of String, File, or Class<Script>
    private Object prepare;
    private Object prepareForAbort;
    private Object commit;
    private Object abort;

    private TransactionManager tm;
    private Configuration cfg;
    private final String groovyShellKey  = ".groovy-" + Integer.toString(hashCode());


    @SuppressWarnings("unchecked")
    public int prepare (long id, Serializable ctx) {
        if (prepare == null)  {
            return PREPARED | NO_JOIN | READONLY; // nothing to do
        }
        try {
            if (compiled) {
                Script s= ((Class<Script>)prepare).newInstance();
                s.setBinding(newBinding(id, ctx));
                return (int)s.run();
            }
            else
                return (int) eval(getShell(id, ctx), prepare, scriptNames.get("prepare"));
        } catch (Exception e) {
            error(e);
        }
        return ABORTED;
    }

    @SuppressWarnings("unchecked")
    public int prepareForAbort (long id, Serializable ctx) {
        if (prepareForAbort == null) {
            return PREPARED | NO_JOIN | READONLY; // nothing to do
        }
        try {
            if (compiled) {
                Script s= ((Class<Script>)prepareForAbort).newInstance();
                s.setBinding(newBinding(id, ctx));
                return (int)s.run();
            }
            else
                return (int) eval(getShell(id, ctx), prepareForAbort, scriptNames.get("prepare-for-abort"));
        } catch (Exception e) {
            error(e);
        }
        return ABORTED;
    }

    @SuppressWarnings("unchecked")
    public void commit(long id, Serializable ctx) {
        if (commit != null) {
            try {
                if (compiled) {
                    Script s= ((Class<Script>)commit).newInstance();
                    s.setBinding(newBinding(id, ctx));
                    s.run();
                }
                else
                    eval(getShell(id, ctx), commit, scriptNames.get("commit"));
            } catch (Exception e) {
                error(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void abort(long id, Serializable ctx) {
        if (abort != null) {
            try {
                if (compiled) {
                    Script s= ((Class<Script>)abort).newInstance();
                    s.setBinding(newBinding(id, ctx));
                    s.run();
                }
                else
                    eval(getShell(id, ctx), abort, scriptNames.get("abort"));
            } catch (Exception e) {
                error(e);
            }
        }
    }

    public void setTransactionManager (TransactionManager tm) {
        this.tm = tm;
    }

    @Override
    public void setConfiguration(Configuration cfg) throws ConfigurationException {
        this.cfg = cfg;
    }

    @Override
    public void setConfiguration(Element e) throws ConfigurationException {
        ClassLoader thisCL= this.getClass().getClassLoader();
        URL scriptURL= thisCL.getResource("org/jpos/transaction/ContextDefaults.groovy");
        GroovySetup.runScriptOnce(scriptURL);

        compiled= cfg.getBoolean("compiled", true);
        if (compiled) {
            gcl= new GroovyClassLoader();
            // TODO: We can add CompilerConfiguration to set a JDK8 target
            // Also, using CompilationCustomizer's I think we can mandate a @CompileStatic
            // as explained in http://docs.groovy-lang.org/latest/html/documentation/#_static_compilation_by_default
            // (start by reading the sections above that link)
            // This @CompileStatic could be added as a flag in an XML attribute
            // Other options could include adding some default imports to the script
        }

        if (e.getChild("prepare") == null)                          // avoid typos, for instance
            warn("GroovyParticipant without 'prepare' element.");

        prepare = getScript(e.getChild("prepare"));
        prepareForAbort = getScript(e.getChild("prepare-for-abort"));
        commit = getScript(e.getChild("commit"));
        abort = getScript(e.getChild("abort"));
    }


    /** Returns a String, a File, or a fully parsed Class&lt;groovy.lang.Script&gt;
    */
    private Object getScript (Element e) throws ConfigurationException
    {
        if (e != null)
        {
            String elName=  e.getName();
            String src=     e.getAttributeValue("src");
            String srcText= null;
            File f=         null;

            if (src != null)
            {
                scriptNames.put(elName, src);                   // the file path, as given
                f= new File(src);
                if (!f.canRead())
                    throw new ConfigurationException ("Can't read '" + src + "'");
                if (!compiled)
                    return f;
            }
            else
            {
                scriptNames.put(elName, "GroovyParticipant<"+elName+">:"+getRealm());
                srcText= e.getText();   // this returns, at worst, an empty String
                if (!compiled)
                    return srcText;
            }

            // Here we can assume compiled == true (or it would have returned from the block above).
            // So we pre-compile the script into a Class<Script> object
            // that will be instantiated for each invocation of the participant's method
            try
            {
                Class clazz;
                if (f != null)
                    clazz= gcl.parseClass(f);
                else
                    clazz= gcl.parseClass(srcText, scriptNames.get(elName));

                // We only support Groovy Scripts, not classes
                if (!Script.class.isAssignableFrom(clazz))
                {
                    throw new ConfigurationException(
                        "Groovy code for '"+scriptNames.get(elName)+"' "+
                        "must be a simple script, not a class."
                    );
                }
                return clazz;
            }
            catch (IOException ex)
            {
                throw new ConfigurationException(ex.getMessage(), ex.getCause());
            }
        }

        return null;    // nothing to process
    }

    private Object eval (GroovyShell shell, Object script, String name) throws IOException {
        if (script instanceof File)
            return shell.evaluate((File)script);
        else if (script instanceof String)
            return shell.evaluate((String)script, name);
        return null;
    }

    private GroovyShell getShell (long id, Serializable context) {
        GroovyShell shell;
        if (context instanceof Context) {
            Context ctx = (Context) context;
            shell = (GroovyShell) ctx.get(groovyShellKey);
            if (shell == null) {
                shell = new GroovyShell(newBinding(id, ctx));
                ctx.put (groovyShellKey, shell);
            }
        } else {
            shell = new GroovyShell(newBinding(id, context));
        }
        return shell;
    }

    private Binding newBinding (long id, Serializable ctx) {
        Binding binding = new Binding();
        binding.setVariable("id", id);
        binding.setVariable("ctx", ctx);
        binding.setVariable("log", this);
        binding.setVariable("tm", tm);
        binding.setVariable("cfg", cfg);
        return binding;
    }
}
