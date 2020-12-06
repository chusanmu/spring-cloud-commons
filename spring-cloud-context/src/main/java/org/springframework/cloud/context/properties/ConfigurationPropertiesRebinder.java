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

package org.springframework.cloud.context.properties;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.util.ProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TODO: 当EnvironmentChangeEvent触发的时候 重新绑定ConfigurationProperty对象的值
 *
 * Listens for {@link EnvironmentChangeEvent} and rebinds beans that were bound to the
 * {@link Environment} using {@link ConfigurationProperties
 * <code>@ConfigurationProperties</code>}. When these beans are re-bound and
 * re-initialized, the changes are available immediately to any component that is using
 * the <code>@ConfigurationProperties</code> bean.
 *
 * @see RefreshScope for a deeper and optionally more focused refresh of bean components.
 * @author Dave Syer
 *
 */
@Component
@ManagedResource
public class ConfigurationPropertiesRebinder
		implements ApplicationContextAware, ApplicationListener<EnvironmentChangeEvent> {

	private ConfigurationPropertiesBeans beans;

	private ApplicationContext applicationContext;

	private Map<String, Exception> errors = new ConcurrentHashMap<>();

	public ConfigurationPropertiesRebinder(ConfigurationPropertiesBeans beans) {
		this.beans = beans;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * A map of bean name to errors when instantiating the bean.
	 * @return The errors accumulated since the latest destroy.
	 */
	public Map<String, Exception> getErrors() {
		return this.errors;
	}

	@ManagedOperation
	public void rebind() {
		// TODO: 清空所有的异常错误
		this.errors.clear();
		// TODO: 拿到被@ConfigurationProperty标注的所有的bean的名字
		for (String name : this.beans.getBeanNames()) {
			// TODO: 进行重新绑定
			rebind(name);
		}
	}

	@ManagedOperation
	public boolean rebind(String name) {
		// TODO: 如果你要重新绑定的name在beans里面不存在，那就直接返回false了
		if (!this.beans.getBeanNames().contains(name)) {
			return false;
		}
		if (this.applicationContext != null) {
			try {
				// TODO: 把bean拿出来
				Object bean = this.applicationContext.getBean(name);
				// TODO: 如果是代理类，就把它原始的bean拿出来
				if (AopUtils.isAopProxy(bean)) {
					bean = ProxyUtils.getTargetObject(bean);
				}
				if (bean != null) {
					// TODO: 不能刷新的配置包含 当前配置那就直接返回false了
					// TODO: determine a more general approach to fix this.
					// see https://github.com/spring-cloud/spring-cloud-commons/issues/571
					if (getNeverRefreshable().contains(bean.getClass().getName())) {
						return false; // ignore
					}
					// TODO: 把这个bean进行销毁，直接就destroy了, 调用生命周期的destroy方法
					this.applicationContext.getAutowireCapableBeanFactory()
							.destroyBean(bean);
					// TODO: 进行重新初始化，这个过程执行完成之后，最新的配置就赋值上去了
					this.applicationContext.getAutowireCapableBeanFactory()
							.initializeBean(bean, name);
					// TODO: 最后返回成功
					return true;
				}
			}
			catch (RuntimeException e) {
				this.errors.put(name, e);
				throw e;
			}
			catch (Exception e) {
				this.errors.put(name, e);
				throw new IllegalStateException("Cannot rebind to " + name, e);
			}
		}
		return false;
	}


	/**
	 * 返回不能刷新的配置
	 * @return
	 */
	@ManagedAttribute
	public Set<String> getNeverRefreshable() {
		String neverRefresh = this.applicationContext.getEnvironment().getProperty(
				"spring.cloud.refresh.never-refreshable",
				"com.zaxxer.hikari.HikariDataSource");
		return StringUtils.commaDelimitedListToSet(neverRefresh);
	}

	@ManagedAttribute
	public Set<String> getBeanNames() {
		return new HashSet<>(this.beans.getBeanNames());
	}

	@Override
	public void onApplicationEvent(EnvironmentChangeEvent event) {
		if (this.applicationContext.equals(event.getSource())
				// Backwards compatible
				|| event.getKeys().equals(event.getSource())) {
			// TODO: 进行重新绑定
			rebind();
		}
	}

}
