package it.cnr.saks.hyperion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

public class MethodEnumerator implements Iterable<MethodDescriptor> {
    private static final Logger log = LoggerFactory.getLogger(MethodEnumerator.class);
    private final List<MethodDescriptor> methods = new ArrayList<>();
    private final Hashtable<String, ArrayList<MethodDescriptor>> beforeMethods = new Hashtable<>();
    private final URL[] classPath;
    private final List<Class> classes;
    private final List<Class> SUTClasses;

    public MethodEnumerator(String classPath, String SUTPath) throws IOException, AnalyzerException {
        this.classPath = this.initializeClasspath(classPath, SUTPath);
        this.classes = this.enumerateClasses(classPath);
        this.SUTClasses = this.enumerateClasses(SUTPath); // To load classes from the SUT

        for (Class klass: this.classes) {
            System.out.print("Analysing class " + klass.getName() + ":");

            if(Modifier.isAbstract(klass.getModifiers())) {
                log.info(" skipping, it's an abstract class.");
                continue;
            }

            log.info(" retrieving valid methods...");
            Method[] methods = this.getAccessibleMethods(klass);
            for(Method currentMethod: methods) {
                boolean isTest = false;
                boolean isBefore = false;

                if(!currentMethod.getDeclaringClass().getName().equals(klass.getName()))
                    continue;

                for(Annotation ann: currentMethod.getAnnotations()) {
                    if(ann.toString().equals("@org.junit.Before()")) {
                        isBefore = true;
                        break;
                    }
                    if(ann.toString().contains("@org.junit.Test")) {
                        isTest = true;
                        break;
                    }
                }

                if(isBefore) {
                    if(!beforeMethods.containsKey(klass.getName())) {
                        ArrayList<MethodDescriptor> beforeMethods = new ArrayList<>();
                        beforeMethods.add(new MethodDescriptor(currentMethod, currentMethod.getName(), this.getMethodDescriptor(currentMethod), klass.getName()));
                        this.beforeMethods.put(klass.getName(), beforeMethods);
                    } else {
                        beforeMethods.get(klass.getName()).add(new MethodDescriptor(currentMethod, currentMethod.getName(), this.getMethodDescriptor(currentMethod), klass.getName()));
                    }
                    continue;
                }

                if(!isTest)
                    continue;

                this.methods.add(new MethodDescriptor(currentMethod, currentMethod.getName(), this.getMethodDescriptor(currentMethod), klass.getName()));
            }
        }
    }

    @Override
    public Iterator<MethodDescriptor> iterator() {
        return this.methods.iterator();
    }
    
    public List<MethodDescriptor> getBeforeMethods(String klass) {
        return this.beforeMethods.get(klass);
    }

    public Class findClass(String fqn) throws ClassNotFoundException {
        for (Class klass: this.classes) {
            if(klass.getName().equals(fqn))
                return klass;
        }
        for (Class klass: this.SUTClasses) {
            if(klass.getName().equals(fqn))
                return klass;
        }
        throw new ClassNotFoundException("Unable to find class " + fqn);
    }

    private String getMethodDescriptor(Method m)
    {
        StringBuilder s= new StringBuilder("(");
        for(final Class klass:(m.getParameterTypes()))
            s.append(this.getDescriptorForClass(klass));
        s.append(')');
        return s + this.getDescriptorForClass(m.getReturnType());
    }

    private String getDescriptorForClass(final Class klass)
    {
        if(klass.isPrimitive())
        {
            if(klass==byte.class)
                return "B";
            if(klass==char.class)
                return "C";
            if(klass==double.class)
                return "D";
            if(klass==float.class)
                return "F";
            if(klass==int.class)
                return "I";
            if(klass==long.class)
                return "J";
            if(klass==short.class)
                return "S";
            if(klass==boolean.class)
                return "Z";
            if(klass==void.class)
                return "V";
            throw new RuntimeException("Unrecognized primitive "+klass);
        }
        if(klass.isArray()) return klass.getName().replace('.', '/');
        return ('L'+klass.getName()+';').replace('.', '/');
    }

    private Method[] getAccessibleMethods(Class klass)
    {
        List<Method> result = new ArrayList<>();
        while (klass != null) {
            for (Method method: klass.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    result.add(method);
                }
            }
            klass = klass.getSuperclass();
        }
        return result.toArray(new Method[0]);
    }


    private List<Class> enumerateClasses(String classPath) throws IOException, AnalyzerException {
        List<String> paths = new ArrayList<>();
        List<Class> classes = new ArrayList<>();

        Files.find(Paths.get(classPath),
                Integer.MAX_VALUE,
                (filePath, fileAttr) -> fileAttr.isRegularFile() && filePath.toString().endsWith(".class"))
                .forEach(pathVal -> paths.add(pathVal.toString()));


        //ClassPathHacker.addFiles(paths); // TODO: serve?!

        for (String classFile: paths) {
            classes.add(loadClass(classFile, classPath, this.getClassPath()));
        }

        return classes;
    }

    private Class loadClass(String classFile, String path, URL[] urls) throws AnalyzerException {
        String classPkg = classFile.substring(0, classFile.lastIndexOf('.')).replace(path, "").replace(File.separator, ".");

        ClassLoader cl;
        Class<?> dynamicClass;

        try {
            cl = new URLClassLoader(urls);
            dynamicClass = cl.loadClass(classPkg);

            try {
                Class.forName(dynamicClass.getName(), true, dynamicClass.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);  // Can't happen
            }

        } catch (ClassNotFoundException e) {
            throw new AnalyzerException("Unable to find class " + e.getMessage());
        }

        return dynamicClass;
    }

    public int getMethodsCount() {
        return this.methods.size();
    }

    private URL[] initializeClasspath(String classPath, String SUTPath) throws MalformedURLException {
        List<URL> ret = new ArrayList<>();
        ret.add(new File(classPath).toURI().toURL());
        ret.add(new File(SUTPath).toURI().toURL());
        ret.add(new File("data/jre/rt.jar").toURI().toURL());

        String runtimeClasspath = ManagementFactory.getRuntimeMXBean().getClassPath();
        String separator = System.getProperty("path.separator");
        String[] additionalClasspath = runtimeClasspath.split(separator);

        for (String p: additionalClasspath) {
            ret.add(new File(p).toURI().toURL());
        }

        URL[] arr = new URL[ret.size()];
        return ret.toArray(arr);
    }

    public URL[] getClassPath() {
        return this.classPath;
    }

}
