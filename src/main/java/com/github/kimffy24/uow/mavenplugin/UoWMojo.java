package com.github.kimffy24.uow.mavenplugin;

import static pro.jk.ejoker.common.system.enhance.EachUtilx.loop;
import static pro.jk.ejoker.common.system.enhance.EachUtilx.select;
import static pro.jk.ejoker.common.system.enhance.MapUtilx.getOrAdd;
import static pro.jk.ejoker.common.system.enhance.StringUtilx.fmt;
import static pro.jk.ejoker.common.system.helper.Ensure.notNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import net.minidev.json.JSONObject;
import pro.jk.ejoker.common.system.functional.IFunction1;
import pro.jk.ejoker.common.system.functional.IVoidFunction1;
import pro.jk.ejoker.common.utils.ClassNamesScanner;

@Mojo(
		name = "uow-gen",
		requiresDependencyResolution = ResolutionScope.COMPILE,
		defaultPhase = LifecyclePhase.COMPILE,
		executionStrategy = "always"
)
public class UoWMojo extends AbstractMojo {
	
	/**
	 * 这个变量不要自己改，我这里是让release脚本发布时自己替换上发布的版本的；
	 * 默认情况，mvn插件不提供版本时执行的话，mvn会给我们拉取最新版本的；无妨。
	 */
	private static final String CURRENT_UOW_CODEGEN_VERSION =
"0.0.2.2"
			;

	private static final String OUTPUT_PACKAGE_INFO_JAVA_FILE = "package-info.java";

	private static final String OUTPUT_RBIND_JSON_FILE = "uow-gen-rbind-info.json";

	private static final String OUTPUT_MAPPER_JAVA_FILE_TMP = "{}UoWMapper";
	
	private static final String OUTPUT_MAPPER_FILE_TPL = "{}.uow.xml";

	private static final String OUTPUT_SQL_FILE = "uow-gen-all.sql";
	
	private static final String UoWSuperClassName = "com.github.kimffy24.uow.export.skeleton.AbstractAggregateRoot";
	
	private static final String UoWGenerationUtilClassName = "com.github.kimffy24.uow.util.GenerateSqlMapperUtil";
	
	private static final String UoWGenerationUtilMainMethod = "generateSqlMapperF";
	
	private static final String MAPPER_JAVA_FILE_CONTENT_TPL = "package {}; "
//			+ "import org.springframework.stereotype.Repository; "
			+ "import com.github.kimffy24.uow.export.mapper.ILocatorMapper; "
//			+ "@Repository "
			+ "public interface {} extends ILocatorMapper \\{ \\}";
	
	private static final String PACKAGE_INFO_JAVA_FILE_CONTENT_TPL = "package {}; \n\n\n/* ***\n"
			+ "当前这个包由UoW-gen模块生成，请不要手动编辑。(特殊情况下，可以把这个包删除，并用下面命令重新生成。) \n\n"
			+ "参考生成命令: \n\n"
			+ "\tmvn clean compile com.github.kimffy24:uowgen-maven-plugin:" + CURRENT_UOW_CODEGEN_VERSION + ":uow-gen -DoutputSpec={}"
			+ "\n\n"
			+ "如无法正确加载生成的mapper文件，则需要在入口类上加上mybatis的扫描注解\n\n"
			+ "\t@MapperScan(\"{}\")"
			+ "\n\n"
			+ "*/";

	@Parameter(defaultValue = "${project}")
	protected MavenProject project;

	@Parameter(defaultValue = "${project.build.sourceDirectory}")
	private File outputDirectory;

	@Parameter(alias = "outputSpec", property = "outputSpec", readonly = true)
	private String outputSpec;
	
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		checkParameters();
		
		String outputSpecFinalx = outputSpec.replaceAll("\\/+",  "/");
		outputSpecFinalx = outputSpecFinalx.replaceAll("\\/$",  "");
		String outputSpecFinal = outputSpecFinalx + "/uowgenroot";

		// step-0-0 检查并创建输出目录
		File outputDirectoryFinal;
//		File f = outputDirectory;
//		if(!f.exists()) {
//			f.mkdirs();
//		}
		outputDirectoryFinal = new File(outputDirectory, outputSpecFinal);
		List<File> ds = new ArrayList<>();
		File tf = outputDirectoryFinal;
		ds.add(tf);
		while(!outputDirectory.equals((tf = tf.getParentFile()))) {
			ds.add(tf);
		}
		Collections.reverse(ds);
		for(File fSub : ds) {
			if(!fSub.exists()) {
				fSub.mkdirs();
			}
		}
		
		// step-0-1 准备class loader，让mvn插件能读取到工程内所有类
		URLClassLoader classLoader = (URLClassLoader )getClassLoader(this.project);
		
		// step-1 找出所有继承 UoW抽象聚合 的所有子类
		List<Class<?>> uowChildren;
		{
			List<String> foundClassName = new ArrayList<>();
			Set<String> sameFile = new HashSet<>();
			
			Class<?> uowSuperType;
			try {
				uowSuperType = classLoader.loadClass(UoWSuperClassName);
			} catch (ClassNotFoundException e) {
				getLog().error(e);
				throw new RuntimeException(e);
			}
			
			
			URL[] urls = classLoader.getURLs();
			for (URL s : urls) {
				String file = s.getFile();
				if(file.endsWith(".jar"))
					continue;
				if(sameFile.contains(file)) {
					continue;
				} else {
					sameFile.add(file);
					if(!new File(file).exists())
						continue;
				}
				List<String> scanPackagesClass = ClassNamesScanner.scanPackagesClass("", s.getPath());
				scanPackagesClass = scanPackagesClass.stream()/*.map(n -> n.substring(1))*/.collect(Collectors.toList());
				foundClassName.addAll(scanPackagesClass);
			}
	
			uowChildren = foundClassName
					.stream()
					.map(n -> {
						try {
							return classLoader.loadClass(n);
						} catch (ClassNotFoundException e) {
							getLog().error(e);
							throw new RuntimeException(e);
						}
					})
					.filter(uowSuperType::isAssignableFrom)
					.collect(Collectors.toList());
		}
		
		// step-2 生成mapper和sql语句
		StringBuilder sbSqlCollector = new StringBuilder();
		Map<Class<?>, StringBuilder> sbMapperCollectors = new HashMap<>();
		Map<Class<?>, String> mapperClassSimpleNameList = new HashMap<>();
		String mapperClassPrefix = outputSpecFinal.replace("/", ".");
		
		{
			// 生成语句： step-2-0 获取工具类和方法
			Class<?> GenerateSqlMapperUtilClazz;
			try {
				GenerateSqlMapperUtilClazz = classLoader.loadClass(UoWGenerationUtilClassName);
			} catch (ClassNotFoundException e) {
				getLog().error(e);
				throw new RuntimeException(e);
			}
			
			Method methodGenerateSqlMapperF;
			try {
				Method[] methods = GenerateSqlMapperUtilClazz.getMethods();
				List<Method> select = select(methods, m -> UoWGenerationUtilMainMethod.equals(m.getName()));
				methodGenerateSqlMapperF = select.get(0);
			} catch (Exception e) {
				getLog().error(e);
				throw new RuntimeException(e);
			}
			
			IVoidFunction1<Class<?>> parseGo = i -> {
				try {
					sbSqlCollector.append(fmt("\n---\n--- {}\n\n", i.getName()));
					methodGenerateSqlMapperF.invoke(
							null,
							new IVoidFunction1<StringBuilder>() { //sqlHandler
								// 竟然不能直接写lambda，垃圾java！！！
								@Override
								public void trigger(StringBuilder p1) {
									sbSqlCollector.append(p1);
								}
							},
							new IVoidFunction1<StringBuilder>() { // mapperHandler
								@Override
								public void trigger(StringBuilder p1) {
									getOrAdd(sbMapperCollectors, i, () -> new StringBuilder()).append(p1);
								}
							},
							new IFunction1<String, Class<?>>() {
								@Override
								public String trigger(Class<?> p1) {
									String mapperJavaFileNameNoExt = fmt(OUTPUT_MAPPER_JAVA_FILE_TMP, p1.getSimpleName());
									mapperClassSimpleNameList.put(p1, mapperJavaFileNameNoExt);
									return mapperClassPrefix + "." + mapperJavaFileNameNoExt;
								}
							},
							i,
							null,
							null);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					getLog().error(e);
					throw new RuntimeException(e);
				}
			};

			uowChildren.forEach(parseGo::trigger);
//			
//			getLog().info(sbSqlCollector);
//			sbMapperCollectors.forEach((c, sb) -> {
//				getLog().info(c.getName() + ": \n" + sb);
//			});
		}
		
		
		// step-3-1 写入文件 mapper.xml 、 UoWMapper接口类 和 sql参考文件
		writeFile(outputDirectoryFinal, OUTPUT_SQL_FILE, sbSqlCollector.toString());
		sbMapperCollectors.forEach((c, sb) -> {
			writeFile(outputDirectoryFinal, fmt(OUTPUT_MAPPER_FILE_TPL, c.getName()), sb.toString());
		});
		Map<String, String> rbindInfo = new HashMap<>();
		loop(mapperClassSimpleNameList, (k, v) -> {
			writeFile(outputDirectoryFinal, v + ".java", fmt(MAPPER_JAVA_FILE_CONTENT_TPL, mapperClassPrefix, v));
			rbindInfo.put(k.getName(), mapperClassPrefix + "." + v);
		});
		// step-3-2  rbind参照表
		writeFile(outputDirectory, OUTPUT_RBIND_JSON_FILE, JSONObject.toJSONString(rbindInfo));
		
		// step-3-3  生成pakcage-info.java
		writeFile(outputDirectoryFinal, OUTPUT_PACKAGE_INFO_JAVA_FILE,
				fmt(PACKAGE_INFO_JAVA_FILE_CONTENT_TPL, mapperClassPrefix, outputSpecFinalx, mapperClassPrefix));
	}
	
	private void writeFile(File targetDir, final String filename, final String content) {
		File fileFinal = new File(targetDir, filename);
		if(fileFinal.exists()) {
			fileFinal.delete();
		}
		try {
			if(!fileFinal.createNewFile()) {
				getLog().warn(fmt("File[{}] is exists before!", fileFinal));
			}
			FileWriter targetFileWriter = new FileWriter(fileFinal);
			targetFileWriter.write(content);
			targetFileWriter.close();
		} catch (IOException e) {
			getLog().error(e);
			throw new RuntimeException(e);
		}
	}

	private void checkParameters() {
		notNull(project, "project");
		notNull(outputSpec, "outputSpec");
		
//        checkNotNull(thriftExecutable, "thriftExecutable");
//        checkNotNull(generator, "generator");
//        final File thriftSourceRoot = getThriftSourceRoot();
//        notNull(thriftSourceRoot);
//        notNull(!thriftSourceRoot.isFile(), "thriftSourceRoot is a file, not a diretory");
//        checkNotNull(temporaryThriftFileDirectory, "temporaryThriftFileDirectory");
//        checkState(!temporaryThriftFileDirectory.isFile(), "temporaryThriftFileDirectory is a file, not a directory");
//        final File outputDirectory = getOutputDirectory();
//        checkNotNull(outputDirectory);
//        checkState(!outputDirectory.isFile(), "the outputDirectory is a file, not a directory");
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private ClassLoader getClassLoader(MavenProject project) {
		try {
			// 所有的类路径环境，也可以直接用 compilePath
			List classpathElements = project.getCompileClasspathElements();

			classpathElements.add(project.getBuild().getOutputDirectory());
			classpathElements.add(project.getBuild().getTestOutputDirectory());
			// 转为 URL 数组
			URL urls[] = new URL[classpathElements.size()];
			for (int i = 0; i < classpathElements.size(); ++i) {
				urls[i] = new File((String) classpathElements.get(i)).toURL();
			}
			// 自定义类加载器
			return new URLClassLoader(urls, this.getClass().getClassLoader());
		} catch (Exception e) {
			getLog().debug("Couldn't get the classloader.");
			return this.getClass().getClassLoader();
		}
	}

}
