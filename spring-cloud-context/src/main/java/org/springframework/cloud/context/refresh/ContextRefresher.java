/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.context.refresh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.Banner.Mode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.cloud.bootstrap.BootstrapApplicationListener;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * TODO: 负责刷新环境 Environment
 * @author Dave Syer
 * @author Venil Noronha
 */
public class ContextRefresher {

	private static final String REFRESH_ARGS_PROPERTY_SOURCE = "refreshArgs";

	private static final String[] DEFAULT_PROPERTY_SOURCES = new String[] {
			// order matters, if cli args aren't first, things get messy
			CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
			"defaultProperties" };

	private Set<String> standardSources = new HashSet<>(
			Arrays.asList(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.JNDI_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONFIG_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONTEXT_PROPERTY_SOURCE_NAME,
					"configurationProperties"));

	private ConfigurableApplicationContext context;

	private RefreshScope scope;

	public ContextRefresher(ConfigurableApplicationContext context, RefreshScope scope) {
		this.context = context;
		this.scope = scope;
	}

	protected ConfigurableApplicationContext getContext() {
		return this.context;
	}

	protected RefreshScope getScope() {
		return this.scope;
	}

	public synchronized Set<String> refresh() {
		// TODO: 更新环境environment
		Set<String> keys = refreshEnvironment();
		// TODO: 调用RefreshScope的refreshAll方法
		this.scope.refreshAll();
		return keys;
	}

	/**
	 * TODO: refreshEnvironment方法通过创建一个新的ConfigurableApplicationContext去获取最新的environment，然后将新的environment的PropertySouce
	 * TODO: 替换当前Environment的，这样就实现了环境刷新, 此方法可能比较耗时
	 *
	 * @return
	 */
	public synchronized Set<String> refreshEnvironment() {
		Map<String, Object> before = extract(
				this.context.getEnvironment().getPropertySources());
		addConfigFilesToEnvironment();
		Set<String> keys = changes(before,
				extract(this.context.getEnvironment().getPropertySources())).keySet();
		// TODO: refreshEnvironment更新完environment后会发送一个EnvironmentChangeEvent事件，该事件会携带本次更新的配置项的key
		// TODO: 如果是监听EnvironmentChangeEvent事件感知配置文件改变，那么需要注意的是，在监听EnvironmentChangeEvent事件时，调用动态配置
		// TODO: bean的代理对象的getXXX()方法获取到的字段的值还是旧的，因为RefreshScope的refreshAll方法还没有被调用
		this.context.publishEvent(new EnvironmentChangeEvent(this.context, keys));
		return keys;
	}

	/* For testing. */ ConfigurableApplicationContext addConfigFilesToEnvironment() {
		ConfigurableApplicationContext capture = null;
		try {
			StandardEnvironment environment = copyEnvironment(
					this.context.getEnvironment());
			SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class)
					.bannerMode(Mode.OFF).web(WebApplicationType.NONE)
					.environment(environment);
			// Just the listeners that affect the environment (e.g. excluding logging
			// listener because it has side effects)
			builder.application()
					.setListeners(Arrays.asList(new BootstrapApplicationListener(),
							new ConfigFileApplicationListener()));
			capture = builder.run();
			if (environment.getPropertySources().contains(REFRESH_ARGS_PROPERTY_SOURCE)) {
				environment.getPropertySources().remove(REFRESH_ARGS_PROPERTY_SOURCE);
			}
			MutablePropertySources target = this.context.getEnvironment()
					.getPropertySources();
			String targetName = null;
			for (PropertySource<?> source : environment.getPropertySources()) {
				String name = source.getName();
				if (target.contains(name)) {
					targetName = name;
				}
				if (!this.standardSources.contains(name)) {
					if (target.contains(name)) {
						target.replace(name, source);
					}
					else {
						if (targetName != null) {
							target.addAfter(targetName, source);
							// update targetName to preserve ordering
							targetName = name;
						}
						else {
							// targetName was null so we are at the start of the list
							target.addFirst(source);
							targetName = name;
						}
					}
				}
			}
		}
		finally {
			ConfigurableApplicationContext closeable = capture;
			while (closeable != null) {
				try {
					closeable.close();
				}
				catch (Exception e) {
					// Ignore;
				}
				if (closeable.getParent() instanceof ConfigurableApplicationContext) {
					closeable = (ConfigurableApplicationContext) closeable.getParent();
				}
				else {
					break;
				}
			}
		}
		return capture;
	}

	// Don't use ConfigurableEnvironment.merge() in case there are clashes with property
	// source names
	private StandardEnvironment copyEnvironment(ConfigurableEnvironment input) {
		StandardEnvironment environment = new StandardEnvironment();
		MutablePropertySources capturedPropertySources = environment.getPropertySources();
		// Only copy the default property source(s) and the profiles over from the main
		// environment (everything else should be pristine, just like it was on startup).
		for (String name : DEFAULT_PROPERTY_SOURCES) {
			if (input.getPropertySources().contains(name)) {
				if (capturedPropertySources.contains(name)) {
					capturedPropertySources.replace(name,
							input.getPropertySources().get(name));
				}
				else {
					capturedPropertySources.addLast(input.getPropertySources().get(name));
				}
			}
		}
		environment.setActiveProfiles(input.getActiveProfiles());
		environment.setDefaultProfiles(input.getDefaultProfiles());
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("spring.jmx.enabled", false);
		map.put("spring.main.sources", "");
		// gh-678 without this apps with this property set to REACTIVE or SERVLET fail
		map.put("spring.main.web-application-type", "NONE");
		capturedPropertySources
				.addFirst(new MapPropertySource(REFRESH_ARGS_PROPERTY_SOURCE, map));
		return environment;
	}

	private Map<String, Object> changes(Map<String, Object> before,
			Map<String, Object> after) {
		Map<String, Object> result = new HashMap<String, Object>();
		for (String key : before.keySet()) {
			if (!after.containsKey(key)) {
				result.put(key, null);
			}
			else if (!equal(before.get(key), after.get(key))) {
				result.put(key, after.get(key));
			}
		}
		for (String key : after.keySet()) {
			if (!before.containsKey(key)) {
				result.put(key, after.get(key));
			}
		}
		return result;
	}

	private boolean equal(Object one, Object two) {
		if (one == null && two == null) {
			return true;
		}
		if (one == null || two == null) {
			return false;
		}
		return one.equals(two);
	}

	private Map<String, Object> extract(MutablePropertySources propertySources) {
		Map<String, Object> result = new HashMap<String, Object>();
		List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
		for (PropertySource<?> source : propertySources) {
			sources.add(0, source);
		}
		for (PropertySource<?> source : sources) {
			if (!this.standardSources.contains(source.getName())) {
				extract(source, result);
			}
		}
		return result;
	}

	private void extract(PropertySource<?> parent, Map<String, Object> result) {
		if (parent instanceof CompositePropertySource) {
			try {
				List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
				for (PropertySource<?> source : ((CompositePropertySource) parent)
						.getPropertySources()) {
					sources.add(0, source);
				}
				for (PropertySource<?> source : sources) {
					extract(source, result);
				}
			}
			catch (Exception e) {
				return;
			}
		}
		else if (parent instanceof EnumerablePropertySource) {
			for (String key : ((EnumerablePropertySource<?>) parent).getPropertyNames()) {
				result.put(key, parent.getProperty(key));
			}
		}
	}

	@Configuration(proxyBeanMethods = false)
	protected static class Empty {

	}

}
