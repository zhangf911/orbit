/*
Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.container;

import com.ea.orbit.annotation.Config;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.configuration.OrbitProperties;
import com.ea.orbit.configuration.OrbitPropertiesImpl;
import com.ea.orbit.configuration.Secret;
import com.ea.orbit.configuration.SecretManager;
import com.ea.orbit.exception.UncheckedException;
import com.ea.orbit.injection.DependencyRegistry;
import com.ea.orbit.reflect.ClassCache;
import com.ea.orbit.reflect.FieldDescriptor;

import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import javax.inject.Singleton;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class OrbitContainer
{
    private OrbitProperties properties;
    private Map<String, ComponentState> components = new ConcurrentHashMap<>();

    private CompletableFuture<?> stopFuture = new CompletableFuture<>();

    @Config("orbit.providers")
    private List<Object> providers = new ArrayList<>();

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(OrbitContainer.class);

    private final DependencyRegistry registry = new DependencyRegistry()
    {
        @Override
        protected void postInject(final Object o)
        {
            super.postInject(o);
            try
            {
                OrbitContainer.this.injectConfig(o);
            }
            catch (IllegalAccessException e)
            {
                throw new UncheckedException(e);
            }
        }
    };

    public List<Class<?>> getClasses()
    {
        return components.values().stream().map(c -> c.implClass).collect(Collectors.toList());
    }

    public void join() throws ExecutionException, InterruptedException
    {
        stopFuture.get();
    }

    protected static class ComponentState
    {
        boolean initialized;
        Class<?> implClass;
        List<ComponentState> dependsOn = new ArrayList<>();

        Object instance;
        boolean isSingleton;
        boolean isStartable;
    }

    protected enum ContainerState
    {
        CREATED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }

    private ContainerState state = ContainerState.CREATED;

    public void add(final Class<?> componentClass)
    {
        ComponentState state = components.get(componentClass.getName());
        if (state == null)
        {
            state = new ComponentState();
            components.put(componentClass.getName(), state);
            state.isSingleton = componentClass.isAnnotationPresent(Singleton.class);
            if (state.isSingleton)
            {
                registry.addSingleton(componentClass);
            }
            state.isStartable = Startable.class.isAssignableFrom(componentClass);
        }
        state.implClass = componentClass;
    }

    public <T> void addInstance(T instance)
    {
        registry.addSingleton(instance.getClass(), instance);
        ComponentState state = components.get(instance.getClass().getName());
        if (state == null)
        {
            state = new ComponentState();
            components.put(instance.getClass().getName(), state);
        }
        if (state.implClass == null)
        {
            state.implClass = instance.getClass();
        }
        state.instance = instance;
    }

    public void setProperties(final Map<String, Object> properties)
    {
        if (this.properties == null)
        {
            this.properties = new OrbitPropertiesImpl();
            this.properties.putAll(System.getProperties());
            addInstance(this.properties);
        }
        this.properties.putAll(properties);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void injectConfig(Object o, java.lang.reflect.Field f) throws IllegalAccessException
    {
        final Config config = f.getAnnotation(Config.class);
        if (config != null)
        {
            if (Modifier.isFinal(f.getModifiers()))
            {
                throw new RuntimeException("Configurable fields should never be final: " + f);
            }

            f.setAccessible(true);

            if (f.getType() == Integer.TYPE || f.getType() == Integer.class)
            {
                f.set(o, properties.getAsInt(config.value(), (Integer) f.get(o)));
            }
            else if (f.getType() == Boolean.TYPE || f.getType() == Boolean.class)
            {
                f.set(o, properties.getAsBoolean(config.value(), (Boolean) f.get(o)));
            }
            else if (f.getType() == String.class)
            {
                f.set(o, properties.getAsString(config.value(), (String) f.get(o)));
            }
            else if (f.getType() == Secret.class)
            {
                final Secret secret = get(SecretManager.class).decrypt((String) f.get(o));
                f.set(o, secret);
            }
            else if (f.getType().isEnum())
            {
                final String enumValue = properties.getAsString(config.value(), null);
                if (enumValue != null)
                {
                    f.set(o, Enum.valueOf((Class<Enum>) f.getType(), enumValue));
                }
            }
            else if (properties.getAll().get(config.value()) != null)
            {
                final Object val = properties.getAll().get(config.value());
                f.set(o, val);
            }
            else if (List.class.isAssignableFrom(f.getType()))
            {
                if ((properties.getAll().get(config.value()) != null))
                {
                    final Object val = properties.getAll().get(config.value());
                    f.set(o, val);
                }
            }
            else
            {
                throw new UncheckedException("Field type not supported for configuration injection: " + f);
            }
        }
    }

    protected void injectConfig(Object o) throws IllegalAccessException
    {
        for (FieldDescriptor fd : ClassCache.shared.getClass(o.getClass()).getAllInstanceFields())
        {
            injectConfig(o, fd.getField());
        }
    }

    public void inject(Object object)
    {
        registry.inject(object);
    }

    public void postConstruct(final Object o)
    {
        for (Class<?> cl = o.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass())
        {
            for (final Method m : cl.getDeclaredMethods())
            {
                if (m.isAnnotationPresent(PostConstruct.class))
                {
                    try
                    {
                        m.setAccessible(true);
                        m.invoke(o);
                        return;
                    }
                    catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
                    {
                        throw new UncheckedException(e);
                    }
                }
            }
        }
    }

    /**
     * <ol><li>Compiles a list of classes that will be stored inside this container:
     * <ul>
     * <li>Reads the configuration from the properties</li>
     * <li>Looks for component declarations in the classpath: META-INF/services/orbit/*</li>
     * </ul>
     * </li>
     * <li>Instantiates all the components</li>
     * <li>Wires the components together by injecting fields and the configuration </li>
     * <li>Calls &#64;PostConstructor on all components</li>
     * <li>Calls start on all components that implement Startable</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public void start()
    {
        logger.info("Starting Orbit Container");

        if (state != ContainerState.CREATED)
        {
            throw new IllegalStateException(state.toString());
        }
        state = ContainerState.STARTING;
        try
        {
            if (properties == null)
            {
                URL res = getClass().getResource("/conf/orbit.yaml");
                if (res != null)
                {

                    Yaml yaml = new Yaml();
                    final Iterable<Object> iter = yaml.loadAll(res.openStream());
                    setProperties((Map<String, Object>) iter.iterator().next());
                }
                else
                {
                    setProperties(Collections.emptyMap());
                }
            }
            addInstance(this);
            injectConfig(this);
            addInstance(registry);
            registry.addSingleton(OrbitProperties.class, properties);

            if (providers != null)
            {
                for (final Object service : providers)
                {
                    if(logger.isDebugEnabled())
                    {
                        logger.debug("Adding Provider: {0}", service.toString());
                    }

                    add((service instanceof Class) ? (Class<?>) service : Class.forName(String.valueOf(service)));
                }
            }

            // Instantiating modules
            for (ComponentState state : components.values())
            {
                if (state.instance == null && Module.class.isAssignableFrom(state.implClass))
                {
                    state.instance = registry.locate(state.implClass);
                }
            }

            // Getting module classes
            Set<Class<?>> newComps = new LinkedHashSet<>();
            for (ComponentState state : components.values())
            {
                if (state.instance != null && Module.class.isAssignableFrom(state.implClass))
                {
                    newComps.addAll(((Module) state.instance).getClasses());
                }
            }
            newComps.forEach(c -> add(c));

            // component snapshot
            Set<ComponentState> comps = new LinkedHashSet<>(components.values());

            // Instantiating startables
            for (ComponentState state : comps)
            {
                if (state.instance == null && state.isSingleton && Startable.class.isAssignableFrom(state.implClass))
                {
                    state.instance = registry.locate(state.implClass);
                }
            }

            // just in case the post constructor added new components
            if (components.size() != comps.size())
            {
                comps.addAll(components.values());
            }

            List<Task<?>> futures = new ArrayList<>();
            // Call start methods
            for (ComponentState state : comps)
            {
                if (state.instance != null && state.instance instanceof Startable)
                {

                    final Task<?> future = ((Startable) state.instance).start();
                    if (future != null && !future.isDone())
                    {
                        futures.add(future);
                    }
                }
            }
            if (futures.size() > 0)
            {
                Task.allOf(futures).join();
            }
            state = ContainerState.STARTED;

            logger.info("Orbit Container started");
        }
        catch (Throwable ex)
        {
            state = ContainerState.FAILED;
            throw new UncheckedException(ex);
        }
    }

    public void stop()
    {
        logger.info("Stopping Orbit Container");
        if (state != ContainerState.STARTED)
        {
            throw new IllegalStateException(state.toString());
        }

        state = ContainerState.STOPPING;
        for (ComponentState componentState : components.values())
        {
            if (componentState.instance != null && componentState.instance instanceof Startable)
            {
                try
                {
                    ((Startable) componentState.instance).stop();
                }
                catch (Exception e)
                {
                    state = ContainerState.FAILED;
                    throw new UncheckedException("Error stopping " + componentState.implClass.getName(), e);
                }
            }
        }
        stopFuture.complete(null);
        state = ContainerState.STOPPED;
        logger.info("Orbit Container stopped");
    }


    protected ComponentState getState(final Class<?> componentClass)
    {
        ComponentState state = components.get(componentClass.getName());
        if (state != null && state.instance != null)
        {
            return state;
        }
        for (ComponentState s : components.values())
        {
            if (s.instance != null && componentClass.isInstance(s.instance))
            {
                return s;
            }
        }
        return state;
    }


    @SuppressWarnings("unchecked")
    public <T> T get(final Class<T> componentClass)
    {
        ComponentState state = getState(componentClass);
        if (state != null && state.instance != null)
        {
            return (T) state.instance;
        }
        return registry.locate(componentClass);
    }

    public List<Object> components()
    {
        List<Object> list = new ArrayList<>(components.size());
        for (ComponentState state : components.values())
        {
            if (state.instance != null)
            {
                list.add(state.instance);
            }
        }
        return Collections.unmodifiableList(list);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> components(final Class<T> actorClass)
    {
        List<T> list = new ArrayList<T>();
        for (ComponentState s : components.values())
        {
            if (s.instance != null && actorClass.isInstance(s.instance))
            {
                list.add((T) s.instance);
            }
        }
        return list;
    }

}
