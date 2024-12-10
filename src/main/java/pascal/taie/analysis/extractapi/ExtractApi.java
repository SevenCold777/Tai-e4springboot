package pascal.taie.analysis.extractapi;

import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.extractapi.pojo.MethodRouter;
import pascal.taie.analysis.extractapi.pojo.Router;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.classes.JMethod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtractApi extends ProgramAnalysis {

    public static  final String ID = "extractApi";
    public DefaultCallGraph cg;
    public List<Router> routers = new ArrayList<>();
    public ExtractApi(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Object analyze() {
        cg = World.get().getResult("cg");
        World.get().getClassHierarchy().applicationClasses().forEach(jClass -> {
            AtomicReference<Boolean> flag = new AtomicReference<>(false);
            ArrayList<MethodRouter> methodRouters = new ArrayList<>();
            jClass.getDeclaredMethods().forEach(jMethod -> {
                //判断method是否有Mapping注解
                for (Annotation method_annotation : jMethod.getAnnotations()) {
                    if (method_annotation.getType().matches("org.springframework.web.bind.annotation.\\w+Mapping")) {
                        flag.set(true);
                        List<String> Params = new ArrayList<>();
                        //获取method的注解内容并添加进methodRouter类
                        for (int i=0; i<jMethod.getParamCount(); i++) {
                            for (Annotation annotation : jMethod.getParamAnnotations(i)) {
                                if (annotation.getType().matches("org.springframework.web.bind.annotation.RequestParam")) {
                                    if (annotation.hasElement("value")) {
                                        String p = Objects.requireNonNull(annotation.getElement("value")).toString();
                                        Params.add(jMethod.getParamType(i) + " " + p.substring(1, p.length() - 1));
                                    }
                                    else {
                                        Params.add(jMethod.getParamType(i) + " " + jMethod.getParamName(i));
                                    }
                                }
                            }
                        }
                        MethodRouter methodRouter = new MethodRouter(method_annotation.getType().split("\\.")[5],jMethod.getName(), formatMappedPath(getPathFromAnnotation(jMethod.getAnnotations())),Params);
                        methodRouters.add(methodRouter);
                        printCG(jMethod);
                    }
                }
            });
            if (flag.get()) {
                //获得class的注解并加入router里
                Router router = new Router(jClass.getName(), formatMappedPath(getPathFromAnnotation(jClass.getAnnotations())), methodRouters);
                routers.add(router);
            }
        });
        //将内容打印出来
        //printRouters();
        return null;
    }
    public String getPathFromAnnotation(Collection<Annotation> annotations) {
        ArrayList<String> path = new ArrayList<>();
        annotations.stream().filter(annotation -> annotation.getType().matches("org.springframework.web.bind.annotation.\\w+Mapping")).forEach(annotation -> {
                if (annotation.hasElement("value")) {
                    path.add(Objects.requireNonNull(annotation.getElement("value")).toString());
                }
                else if (annotation.hasElement("path")) {
                    path.add(Objects.requireNonNull(annotation.getElement("path")).toString());
                }
        });
        return path.size() == 1 ? path.get(0) : null;
    }
    public void printRouters() {
        try {
            FileWriter fw = new FileWriter("./result/url_result/URL.txt");
            int total = 0;
            for (Router router : routers) {
                List<String> completePathFromRouter = getCompletePathFromRouter(router);
                for (String path : completePathFromRouter) {
                    System.out.println(path);
                    fw.write(path + "\n");
                    total = total + 1;
                }
            }
            fw.write("-------------------------共识别出" + total + "个URL入口及其关联函数----------------------------");
            fw.close();
            System.out.println("-------------------------共识别出" + total + "个URL入口及其关联函数----------------------------");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public List<String> getCompletePathFromRouter(Router router) {
        ArrayList<String> routerList = new ArrayList<>();
        String classPath = router.classPath();
        String className = router.className();

        router.methodRouters().forEach(methodRouter -> {
            String pathMethod = methodRouter.path();
            String callMethod = methodRouter.methodName();
            String reqtype = methodRouter.RequestType();
            String Params = "";
            if (!methodRouter.Params().isEmpty()) {
                for (String param : methodRouter.Params()) {
                    Params = Params + param + ", ";
                }
                Params = Params.substring(0,Params.length()-2);
            }
            routerList.add("RequestType : " + reqtype.split("M")[0] + ", URL : " + classPath + pathMethod + ", CallMethod : " + className + ":" + callMethod + "(" + Params + ")");
        });
        return routerList;
    }
    public String formatMappedPath(String originPath) {
        String path = null;
        if (originPath == null) {
            return "";
        }
        Pattern pattern = Pattern.compile("\\{\"(.*?)\"\\}");
        Matcher matcher = pattern.matcher(originPath);
        if (matcher.find()) {
            path = matcher.group(1); // Extract the text between curly braces
        }
        if (path == null) {
            return "";
        }
        //  /path/ => /path
        if (path.matches("/.*") && path.matches(".*/")) {
            return path.substring(0, path.length() - 1);
        }
        // path/ => /path
        if (path.matches(".*/") && !path.matches("/.*")) {
            return "/" + path.substring(0, path.length() - 1);
        }
        // path => /path
        if (!path.matches("/.*") && !path.matches(".*/")) {
            return "/" + path;
        }
        // /path => /path
        return path;
    }
    public void printCG(JMethod method){
        AtomicInteger total = new AtomicInteger();
        Set<JMethod> done = new HashSet<>();
        List<JMethod> worklist = new ArrayList<>();
        ApiCallGraph df = new ApiCallGraph();
        df.addEntryMethod(method);
        worklist.add(method);
        AtomicReference<String> cgres = new AtomicReference<>("");
        while (!worklist.isEmpty()){
            JMethod jm = worklist.remove(0);
            df.addReachableMethod(jm);
            done.add(jm);
            cg.getCallSitesIn(jm).forEach(invoke -> {
                cg.edgesOutOf(invoke).forEach(edge -> {
                    df.addEdge(edge);
                    if ((!worklist.contains(edge.getCallee())) && (!jm.getDeclaringClass().getName().matches("^(java\\.|sun\\.|javax\\.|com\\.sun\\.).+$")) && (!done.contains(edge.getCallee()))){
                        worklist.add(edge.getCallee());
                        System.out.println(jm.toString() + " -> " + edge.getCallee().toString());
                        cgres.set(cgres + jm.toString() + " -> " + edge.getCallee().toString() + "\n");
                        total.set(total.get() + 1);
                    }
                });
            });
        }
        File outputDir = new File("./result/cg_result/");
        CallGraphs.dumpCallGraph(df, new File(outputDir, method.getDeclaringClass().getSimpleName() + "_" + method.getName() + ".dot"));
        try {
            FileWriter fw = new FileWriter("./result/cg_result/" + method.getDeclaringClass().getSimpleName() + "_" + method.getName() + ".txt");
            fw.write(String.valueOf(cgres));
            fw.write("-------------------------该入口函数共有" + total + "条调用流----------------------------");
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("-------------------------该入口函数共有" + total + "条调用流----------------------------");
    }
}
