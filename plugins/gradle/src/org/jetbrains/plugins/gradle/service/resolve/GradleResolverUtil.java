/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.gradle.service.resolve;

import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrNamedArgument;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.GrReferenceExpressionImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GrImplicitVariableImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GrLightMethodBuilder;
import org.jetbrains.plugins.groovy.lang.psi.impl.synthetic.GrLightParameter;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyPropertyUtils;

import java.util.Set;

/**
 * @author Vladislav.Soroka
 * @since 8/30/13
 */
public class GradleResolverUtil {
  private static final Key<Integer> TYPE_RESOLVE_IN_PROGRESS_KEY = Key.create("TYPE_RESOLVE_IN_PROGRESS_KEY");

  public static int getGrMethodArumentsCount(@NotNull GrArgumentList args) {
    int argsCount = 0;
    boolean namedArgProcessed = false;
    for (GroovyPsiElement arg : args.getAllArguments()) {
      if (arg instanceof GrNamedArgument) {
        if (!namedArgProcessed) {
          namedArgProcessed = true;
          argsCount++;
        }
      }
      else {
        argsCount++;
      }
    }
    return argsCount;
  }

  public static void addImplicitVariable(@NotNull PsiScopeProcessor processor,
                                         @NotNull ResolveState state,
                                         @NotNull GrReferenceExpressionImpl expression,
                                         @NotNull String type) {
    if (expression.getQualifier() == null) {
      PsiVariable myPsi = new GrImplicitVariableImpl(expression.getManager(), expression.getReferenceName(), type, expression);
      processor.execute(myPsi, state);
    }
  }

  public static void addImplicitVariable(@NotNull PsiScopeProcessor processor,
                                         @NotNull ResolveState state,
                                         @NotNull PsiElement element,
                                         @NotNull String type) {
    PsiVariable myPsi = new GrImplicitVariableImpl(element.getManager(), element.getText(), type, element);
    processor.execute(myPsi, state);
  }


  @Nullable
  public static GrLightMethodBuilder createMethodWithClosure(@NotNull String name,
                                                             @Nullable String returnType,
                                                             @Nullable String closureTypeParameter,
                                                             @NotNull PsiElement place,
                                                             @NotNull GroovyPsiManager psiManager) {
    PsiClassType closureType;
    PsiClass closureClass =
      psiManager.findClassWithCache(GroovyCommonClassNames.GROOVY_LANG_CLOSURE, place.getResolveScope());
    if (closureClass == null) return null;

    PsiElementFactory factory = JavaPsiFacade.getElementFactory(place.getManager().getProject());

    if (closureTypeParameter != null) {
      PsiClassType closureClassTypeParameter =
        factory.createTypeByFQClassName(closureTypeParameter, place.getResolveScope());
      closureType = factory.createType(closureClass, closureClassTypeParameter);
    }
    else {
      PsiClassType closureClassTypeParameter =
        factory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_OBJECT, place.getResolveScope());
      closureType = factory.createType(closureClass, closureClassTypeParameter);
    }

    GrLightMethodBuilder methodWithClosure = new GrLightMethodBuilder(place.getManager(), name);
    GrLightParameter closureParameter = new GrLightParameter("closure", closureType, methodWithClosure);
    methodWithClosure.addParameter(closureParameter);
    PsiClassType retType = factory.createTypeByFQClassName(
      returnType != null ? returnType : CommonClassNames.JAVA_LANG_OBJECT, place.getResolveScope());
    methodWithClosure.setReturnType(retType);
    methodWithClosure.setContainingClass(retType.resolve());
    return methodWithClosure;
  }

  public static void processMethod(@NotNull String gradleConfigurationName,
                                   @NotNull PsiClass dependencyHandlerClass,
                                   @NotNull PsiScopeProcessor processor,
                                   @NotNull ResolveState state,
                                   @NotNull PsiElement place) {
    processMethod(gradleConfigurationName, dependencyHandlerClass, processor, state, place, null);
  }

  public static void processMethod(@NotNull String methodName,
                                   @NotNull PsiClass handlerClass,
                                   @NotNull PsiScopeProcessor processor,
                                   @NotNull ResolveState state,
                                   @NotNull PsiElement place,
                                   @Nullable String defaultMethodName) {
    GrLightMethodBuilder builder = new GrLightMethodBuilder(place.getManager(), methodName);
    PsiElementFactory factory = JavaPsiFacade.getElementFactory(place.getManager().getProject());
    PsiType type = new PsiArrayType(factory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_OBJECT, place.getResolveScope()));
    builder.addParameter(new GrLightParameter("param", type, builder));
    PsiClassType retType = factory.createTypeByFQClassName(CommonClassNames.JAVA_LANG_OBJECT, place.getResolveScope());
    builder.setReturnType(retType);
    processor.execute(builder, state);

    GrMethodCall call = PsiTreeUtil.getParentOfType(place, GrMethodCall.class);
    if (call == null) {
      return;
    }
    GrArgumentList args = call.getArgumentList();
    if (args == null) {
      return;
    }

    int argsCount = getGrMethodArumentsCount(args);
    argsCount++; // Configuration name is delivered as an argument.

    for (PsiMethod method : handlerClass.findMethodsByName(methodName, false)) {
      if (method.getParameterList().getParametersCount() == argsCount) {
        builder.setNavigationElement(method);
        return;
      }
    }

    // handle setter's shortcut facilities
    final String setter = GroovyPropertyUtils.getSetterName(methodName);
    for (PsiMethod method : handlerClass.findMethodsByName(setter, false)) {
      if (method.getParameterList().getParametersCount() == 1) {
      builder.setNavigationElement(method);
      return;
      }
    }

    if (defaultMethodName != null) {
      for (PsiMethod method : handlerClass.findMethodsByName(defaultMethodName, false)) {
        if (method.getParameterList().getParametersCount() == argsCount) {
          builder.setNavigationElement(method);
          return;
        }
      }
    }
  }

  public static void processDeclarations(@NotNull GroovyPsiManager psiManager,
                                         @NotNull PsiScopeProcessor processor,
                                         @NotNull ResolveState state,
                                         @NotNull PsiElement place,
                                         @NotNull String... fqNames) {
    for (String fqName : fqNames) {
      PsiClass psiClass = psiManager.findClassWithCache(fqName, place.getResolveScope());
      if (psiClass != null) {
        psiClass.processDeclarations(processor, state, null, place);
      }
    }
  }

  @Nullable
  public static PsiElement findParent(@NotNull PsiElement element, int level) {
    PsiElement parent = element;
    do {
      parent = parent.getParent();
    }
    while (parent != null && --level > 0);
    return parent;
  }

  @Nullable
  public static <T extends PsiElement> T findParent(@NotNull PsiElement element, Class<T> clazz) {
    PsiElement parent = element;
    do {
      parent = parent.getParent();
      if (clazz.isInstance(parent)) {
        //noinspection unchecked
        return (T)parent;
      }
    }
    while (parent != null && !(parent instanceof GroovyFile));
    return null;
  }

  public static boolean canBeMethodOf(@Nullable String methodName, @Nullable PsiClass aClass) {
    return methodName != null && aClass != null && aClass.findMethodsByName(methodName, true).length != 0;
  }

  @Nullable
  public static PsiType getTypeOf(@Nullable GrExpression expression) {
    PsiType psiType = null;
    if (expression != null) {
      Integer count = expression.getUserData(TYPE_RESOLVE_IN_PROGRESS_KEY);
      if (count == null) count = 0;
      if (count < 15) {
        expression.putUserData(TYPE_RESOLVE_IN_PROGRESS_KEY, ++count);
        psiType = expression.getNominalType();
        expression.putUserData(TYPE_RESOLVE_IN_PROGRESS_KEY, null);
      }
    }
    return psiType;
  }
}
