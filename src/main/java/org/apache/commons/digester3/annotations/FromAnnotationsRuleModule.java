/* $Id$
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.digester3.annotations;

import static org.apache.commons.digester3.annotations.utils.AnnotationUtils.getAnnotationsArrayValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;

import org.apache.commons.digester3.Rule;
import org.apache.commons.digester3.annotations.reflect.MethodArgument;
import org.apache.commons.digester3.binder.RulesBinder;
import org.apache.commons.digester3.binder.RulesModule;

/**
 * {@link RulesModule} implementation that allows loading rules from
 * annotated classes.
 *
 * @since 3.0
 */
public abstract class FromAnnotationsRuleModule
    implements RulesModule
{

    private static final String JAVA_PACKAGE = "java";

    private static AnnotationHandlerFactory DEFAULT_HANDLER_FACTORY = new DefaultAnnotationHandlerFactory();

    private AnnotationHandlerFactory annotationHandlerFactory = DEFAULT_HANDLER_FACTORY;

    private WithMemoryRulesBinder rulesBinder;

    /**
     * {@inheritDoc}
     */
    public final void configure( RulesBinder rulesBinder )
    {
        if ( this.rulesBinder != null )
        {
            throw new IllegalStateException( "Re-entry is not allowed." );
        }

        if ( rulesBinder instanceof WithMemoryRulesBinder )
        {
            this.rulesBinder = (WithMemoryRulesBinder) rulesBinder;
        }
        else
        {
            this.rulesBinder = new WithMemoryRulesBinder( rulesBinder );
        }

        try
        {
            configure();
        }
        finally
        {
            this.rulesBinder = null;
        }
    }

    /**
     * Configures a {@link Binder} via the exposed methods.
     */
    protected abstract void configure();

    /**
     * 
     */
    protected final void useAnnotationHandlerFactory(AnnotationHandlerFactory annotationHandlerFactory)
    {
        if ( annotationHandlerFactory == null )
        {
            throw new IllegalArgumentException( "Argument 'annotationHandlerFactory' must be not null" );
        }

        this.annotationHandlerFactory = annotationHandlerFactory;
    }

    /**
     * 
     */
    protected final void useDefaultAnnotationHandlerFactory()
    {
        useAnnotationHandlerFactory( DEFAULT_HANDLER_FACTORY );
    }

    /**
     * 
     *
     * @param type
     */
    protected final void bindRulesFrom(final Class<?> type)
    {
        if ( type == null || type.getPackage().getName().startsWith( JAVA_PACKAGE )
            || rulesBinder.isAlreadyBound( type ) )
        {
            return;
        }

        // TYPE
        visitElements( type );

        if ( !type.isInterface() )
        {
            // CONSTRUCTOR
            visitElements( new PrivilegedAction<Constructor<?>[]>()
            {
                public Constructor<?>[] run()
                {
                    return type.getDeclaredConstructors();
                }
            } );

            // FIELD
            visitElements( new PrivilegedAction<Field[]>()
            {
                public Field[] run()
                {
                    return type.getDeclaredFields();
                }
            } );
        }

        // METHOD
        visitElements( new PrivilegedAction<Method[]>()
        {
            public Method[] run()
            {
                return type.getDeclaredMethods();
            }
        } );

        rulesBinder.markAsBound( type );
        bindRulesFrom( type.getSuperclass() );
    }

    /**
     * 
     *
     * @param <AE>
     * @param action
     */
    private <AE extends AnnotatedElement> void visitElements( PrivilegedAction<AE[]> action )
    {
        AE[] annotatedElements = null;
        if ( System.getSecurityManager() != null )
        {
            annotatedElements = AccessController.doPrivileged( action );
        }
        else
        {
            annotatedElements = action.run();
        }
        visitElements( annotatedElements );
    }

    /**
     * 
     *
     * @param annotatedElements
     */
    private void visitElements( AnnotatedElement... annotatedElements )
    {
        for ( AnnotatedElement element : annotatedElements )
        {
            for ( Annotation annotation : element.getAnnotations() )
            {
                handle( annotation, element );
            }

            if ( element instanceof Method )
            {
                // method args
                Method method = (Method) element;

                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                Class<?>[] parameterTypes = method.getParameterTypes();
                for ( int i = 0; i < parameterTypes.length; i++ )
                {
                    visitElements( new MethodArgument( i, parameterTypes[i], parameterAnnotations[i] ) );
                }
            }
        }
    }

    /**
     * Handles the current visited element and related annotation, invoking the
     * right handler putting the rule provider in the rule set.
     *
     * @param annotation the current visited annotation.
     * @param element the current visited element.
     */
    @SuppressWarnings("unchecked")
    private <A extends Annotation, E extends AnnotatedElement, R extends Rule> void handle( A annotation, E element )
    {
        Class<?> annotationType = annotation.annotationType();

        // check if it is one of the @*.List annotation
        if ( annotationType.isAnnotationPresent( DigesterRuleList.class ) )
        {
            Annotation[] annotations = getAnnotationsArrayValue( annotation );
            if ( annotations != null && annotations.length > 0 )
            {
                // if it is an annotations array, process them
                for ( Annotation ptr : annotations )
                {
                    handle( ptr, element );
                }
            }
        }
        else if ( annotationType.isAnnotationPresent( DigesterRule.class ) )
        {
            DigesterRule digesterRule = annotationType.getAnnotation( DigesterRule.class );

            // the default behavior if the handler is not specified
            Class<? extends AnnotationHandler<Annotation, AnnotatedElement>> handlerType =
                (Class<? extends AnnotationHandler<Annotation, AnnotatedElement>>) digesterRule.handledBy();
            try
            {
                AnnotationHandler<Annotation, AnnotatedElement> handler =
                    annotationHandlerFactory.newInstance( handlerType );

                // run!
                handler.handle( annotation, element, this.rulesBinder );
            }
            catch ( Exception e )
            {
                rulesBinder.addError( e );
            }
        }
    }

}
