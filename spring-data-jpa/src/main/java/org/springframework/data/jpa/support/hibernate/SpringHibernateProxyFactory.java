/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.support.hibernate;

import static org.springframework.util.ReflectionUtils.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.HibernateException;
import org.hibernate.bytecode.spi.BasicProxyFactory;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.internal.util.collections.ArrayHelper;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.ProxyConfiguration;
import org.hibernate.proxy.ProxyFactory;
import org.hibernate.type.CompositeType;
import org.springframework.data.util.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 * @author Christoph Strobl
 * @since 2023/01
 */
public class SpringHibernateProxyFactory implements ProxyFactory, BasicProxyFactory {

	private static final Log LOGGER = LogFactory.getLog(SpringHibernateProxyFactory.class);
	private final org.springframework.aop.framework.ProxyFactory proxyFactory;

	private Class<?> persistentClass;
	private String entityName;
	private Class<?>[] interfaces;
	private Method getIdentifierMethod;
	private Method setIdentifierMethod;
	private CompositeType componentIdType;
	private boolean overridesEquals;

	private Class<?> proxyClass;
	Lazy<Object> proxy = null;

	public SpringHibernateProxyFactory() {
		this.proxyFactory = new org.springframework.aop.framework.ProxyFactory();
	}

	public SpringHibernateProxyFactory(Class<?> superClassOrInterface) {

		this.persistentClass = superClassOrInterface;

		this.proxyFactory = new org.springframework.aop.framework.ProxyFactory();
		this.proxyFactory.setTargetClass(superClassOrInterface);
		this.proxyClass = proxyFactory.getProxyClass(superClassOrInterface.getClassLoader());
	}

	@Override
	public void postInstantiate(String entityName, Class<?> persistentClass, Set<Class<?>> interfaces,
			Method getIdentifierMethod, Method setIdentifierMethod, CompositeType componentIdType) throws HibernateException {

		this.entityName = entityName;
		this.persistentClass = persistentClass;
		this.interfaces = toArray(interfaces);
		this.getIdentifierMethod = getIdentifierMethod;
		this.setIdentifierMethod = setIdentifierMethod;
		this.componentIdType = componentIdType;
		this.overridesEquals = ReflectHelper.overridesEquals(persistentClass);

		proxyFactory.setTargetClass(persistentClass);
		proxyFactory.setInterfaces(this.interfaces);
		if (!persistentClass.isInterface() && Modifier.isPublic(persistentClass.getModifiers())
				&& !Modifier.isFinal(persistentClass.getModifiers()) && !persistentClass.isHidden()
				&& persistentClass != Class.class) {
			proxyFactory.setProxyTargetClass(true);
		}


	}

	public Class<?> getProxyClass() {
		return proxyClass;
	}

	@Override
	public HibernateProxy getProxy(Object id, SharedSessionContractImplementor session) throws HibernateException {

		LOGGER.debug("SpringProxy created for: " + this.persistentClass);

		proxyFactory.addAdvice(new SpringHibernateMethodInterceptor(id, session));

		this.proxy = Lazy.of(proxyFactory.getProxy());
		if (proxy.get() instanceof ProxyConfiguration proxyConfig) {

			SpringLazyLoadingInterceptor interceptor = new SpringLazyLoadingInterceptor(entityName, persistentClass,
					interfaces, id, getIdentifierMethod, setIdentifierMethod, componentIdType, session, overridesEquals);
			proxyConfig.$$_hibernate_set_interceptor(interceptor);
		}
		this.proxyClass = proxy.get().getClass();
		return (HibernateProxy) proxy.get();
	}

	private Class<?>[] toArray(Set<Class<?>> interfaces) {
		if (interfaces == null) {
			return ArrayHelper.EMPTY_CLASS_ARRAY;
		}

		return interfaces.toArray(new Class[interfaces.size()]);
	}

	@Override
	public Object getProxy() {

		System.out.println("get basic proxy for: " + this.persistentClass);
		return proxyFactory.getProxy(this.persistentClass.getClassLoader());
	}

	public class SpringHibernateMethodInterceptor implements MethodInterceptor {

		private final Object id;
		private final SharedSessionContractImplementor session;
		Object target;

		public SpringHibernateMethodInterceptor(Object id, SharedSessionContractImplementor session) {
			this.id = id;
			this.session = session;
			target = null;
		}

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {

			System.out.println("invoking: " + invocation.getMethod().getName());

			if (invocation.getMethod().getName().equals("getHibernateLazyInitializer")) {
				return new SpringLazyLoadingInterceptor(entityName, persistentClass, interfaces, id, getIdentifierMethod,
						setIdentifierMethod, componentIdType, session, overridesEquals);
			}

			if (isObjectMethod(invocation.getMethod()) && Object.class.equals(invocation.getMethod().getDeclaringClass())) {

				if (ReflectionUtils.isToStringMethod(invocation.getMethod())) {
					return proxyToString(null);
				}

				if (ReflectionUtils.isEqualsMethod(invocation.getMethod())) {
					return proxyEquals(null, invocation.getArguments()[0]);
				}

				if (ReflectionUtils.isHashCodeMethod(invocation.getMethod())) {
					return proxyHashCode();
				}
			}

			if(invocation.getMethod().getName().equals("unsetSession")) {
				return null;
			}

			if(invocation.getMethod().getName().equals("asHibernateProxy")) {
				return proxy.get();
			}

			if(invocation.getMethod().getName().equals("extractLazyInitializer")) {
				return new SpringLazyLoadingInterceptor(entityName, persistentClass,
						interfaces, id, getIdentifierMethod, setIdentifierMethod, componentIdType, session, overridesEquals);
			}

			LOGGER.debug("SpringProxy lazy loading " + entityName + ": " + id);
			target = session.immediateLoad(entityName, id);

			return target != null ? invocation.getMethod().invoke(target, invocation.getArguments()) : null;
		}

		private String proxyToString(@Nullable Object source) {

			StringBuilder description = new StringBuilder();

			description.append(System.identityHashCode(source));
			description.append("$").append(HibernateProxy.class.getSimpleName());

			return description.toString();
		}

		private boolean proxyEquals(@Nullable Object proxy, Object that) {

			if (that == proxy) {
				return true;
			}

			return proxyToString(proxy).equals(that.toString());
		}

		private int proxyHashCode() {
			return proxyToString(this).hashCode();
		}
	}
}