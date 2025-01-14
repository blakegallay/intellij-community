// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.light;

import com.intellij.codeInsight.AnnotationTargetUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.impl.ElementPresentationUtil;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiUtil;
import com.intellij.ui.IconManager;
import com.intellij.ui.PlatformIcons;
import com.intellij.ui.icons.RowIcon;
import com.intellij.util.BitUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.VisibilityIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Arrays;
import java.util.Objects;

public final class LightRecordField extends LightField implements LightRecordMember {
  private final @NotNull PsiRecordComponent myRecordComponent;

  public LightRecordField(@NotNull PsiManager manager,
                          @NotNull PsiField field,
                          @NotNull PsiClass containingClass,
                          @NotNull PsiRecordComponent component) {
    super(manager, field, containingClass);
    myRecordComponent = component;
  }

  @Override
  @NotNull
  public PsiRecordComponent getRecordComponent() {
    return myRecordComponent;
  }

  @Override
  public int getTextOffset() {
    return myRecordComponent.getTextOffset();
  }

  @NotNull
  @Override
  public PsiElement getNavigationElement() {
    return myRecordComponent.getNavigationElement();
  }

  @Override
  public boolean isWritable() {
    return true;
  }

  @Override
  public PsiFile getContainingFile() {
    PsiClass containingClass = getContainingClass();
    if (containingClass == null) return null;
    return containingClass.getContainingFile();
  }

  @Override
  public @NotNull PsiType getType() {
    if (DumbService.isDumb(myRecordComponent.getProject())) return myRecordComponent.getType();
    return CachedValuesManager.getCachedValue(this, () -> {
      PsiType type = myRecordComponent.getType()
        .annotate(() -> Arrays.stream(myRecordComponent.getAnnotations())
          .filter(LightRecordField::hasApplicableAnnotationTarget)
          .toArray(PsiAnnotation[]::new)
        );
      return CachedValueProvider.Result.create(type, this);
    });
  }

  @Override
  public PsiAnnotation @NotNull [] getAnnotations() {
    return getType().getAnnotations();
  }

  @Override
  public boolean hasAnnotation(@NotNull String fqn) {
    PsiType type = getType();
    return type.hasAnnotation(fqn);
  }

  @Override
  public @Nullable PsiAnnotation getAnnotation(@NotNull String fqn) {
    return getType().findAnnotation(fqn);
  }

  @Override
  public Icon getElementIcon(final int flags) {
    IconManager iconManager = IconManager.getInstance();
    RowIcon baseIcon =
      iconManager.createLayeredIcon(this, iconManager.getPlatformIcon(PlatformIcons.Field), ElementPresentationUtil.getFlags(this, false));
    if (BitUtil.isSet(flags, ICON_FLAG_VISIBILITY)) {
      VisibilityIcons.setVisibilityIcon(PsiUtil.ACCESS_LEVEL_PRIVATE, baseIcon);
    }
    return baseIcon;
  }

  @Override
  public PsiElement getContext() {
    return getContainingClass();
  }

  @Override
  public @NotNull SearchScope getUseScope() {
    PsiClass aClass = Objects.requireNonNull(getContainingClass());
    PsiClass containingClass = aClass.getContainingClass();
    while (containingClass != null) {
      aClass = containingClass;
      containingClass = containingClass.getContainingClass();
    }
    return new LocalSearchScope(aClass);
  }

  private static boolean hasApplicableAnnotationTarget(PsiAnnotation annotation) {
    return AnnotationTargetUtil.findAnnotationTarget(annotation, PsiAnnotation.TargetType.TYPE_USE, PsiAnnotation.TargetType.FIELD) != null;
  }

  @Override
  public void normalizeDeclaration() throws IncorrectOperationException {
    // no-op
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    return o instanceof LightRecordField &&
           myRecordComponent.equals(((LightRecordField)o).myRecordComponent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myRecordComponent);
  }

}
