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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Collects references to <code>@ConfigurationProperties</code> beans in the context and
 * its parent.
 *
 * @author Dave Syer
 */
@Component
public class ConfigurationPropertiesBeans
		implements BeanPostProcessor, ApplicationContextAware {

	private Map<String, ConfigurationPropertiesBean> beans = new HashMap<>();

	private ApplicationContext applicationContext;

	private ConfigurableListableBeanFactory beanFactory;

	private String refreshScope;

	private boolean refreshScopeInitialized;

	private ConfigurationPropertiesBeans parent;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.applicationContext = applicationContext;
		if (applicationContext
				.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory) {
			this.beanFactory = (ConfigurableListableBeanFactory) applicationContext
					.getAutowireCapableBeanFactory();
		}
		if (applicationContext.getParent() != null && applicationContext.getParent()
				.getAutowireCapableBeanFactory() instanceof ConfigurableListableBeanFactory) {
			ConfigurableListableBeanFactory listable = (ConfigurableListableBeanFactory) applicationContext
					.getParent().getAutowireCapableBeanFactory();
			String[] names = listable
					.getBeanNamesForType(ConfigurationPropertiesBeans.class);
			if (names.length == 1) {
				this.parent = (ConfigurationPropertiesBeans) listable.getBean(names[0]);
				// TODO: 把之前存在的全部加进去
				this.beans.putAll(this.parent.beans);
			}
		}
	}

	/**
	 * @param beans The bean meta data to set.
	 * @deprecated since 2.2.0 because
	 * {@link org.springframework.boot.context.properties.ConfigurationBeanFactoryMetadata}
	 * has been deprecated
	 */
	@Deprecated
	public void setBeanMetaDataStore(
			org.springframework.boot.context.properties.ConfigurationBeanFactoryMetadata beans) {
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName)
			throws BeansException {
		// TODO: 判断这个bean是否是refreshScope的，如果是的，直接返回这个bean
		if (isRefreshScoped(beanName)) {
			return bean;
		}
		// TODO: 并且不是refreshScope的
		// TODO: 这句代码执行的就是，如果你是 ConfigurationProperties 标注的bean，那么就拿到你，否则返回null
		ConfigurationPropertiesBean propertiesBean = ConfigurationPropertiesBean
				.get(this.applicationContext, bean, beanName);
		if (propertiesBean != null) {
			// TODO: 放进缓存beans中去
			this.beans.put(beanName, propertiesBean);
		}
		return bean;
	}

	private boolean isRefreshScoped(String beanName) {
		if (this.refreshScope == null && !this.refreshScopeInitialized) {
			this.refreshScopeInitialized = true;
			// TODO: 拿到这个beanFactory所注册的所有的scope
			for (String scope : this.beanFactory.getRegisteredScopeNames()) {
				// TODO: 如果有refreshScope就设置进去
				if (this.beanFactory.getRegisteredScope(
						scope) instanceof org.springframework.cloud.context.scope.refresh.RefreshScope) {
					this.refreshScope = scope;
					break;
				}
			}
		}
		// TODO: 如果beanName为空，或者 refreshScope也是空，就直接返回false
		if (beanName == null || this.refreshScope == null) {
			return false;
		}
		// TODO: 当前beanFactory有这个beanDefinition,并且这个bean的scope是refreshScope就直接返回true
		return this.beanFactory.containsBeanDefinition(beanName) && this.refreshScope
				.equals(this.beanFactory.getBeanDefinition(beanName).getScope());
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName)
			throws BeansException {
		return bean;
	}

	public Set<String> getBeanNames() {
		return new HashSet<String>(this.beans.keySet());
	}

}
