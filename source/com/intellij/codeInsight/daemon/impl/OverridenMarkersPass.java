
package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.Icons;
import gnu.trove.THashMap;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class OverridenMarkersPass extends TextEditorHighlightingPass {
  private static final Icon OVERRIDEN_METHOD_MARKER_RENDERER = IconLoader.getIcon("/gutter/overridenMethod.png");
  private static final Icon IMPLEMENTED_METHOD_MARKER_RENDERER = IconLoader.getIcon("/gutter/implementedMethod.png");
  private static final Icon IMPLEMENTED_INTERFACE_MARKER_RENDERER = IconLoader.getIcon("/gutter/implementedMethod.png");
  private static final Icon SUBCLASSED_CLASS_MARKER_RENDERER = IconLoader.getIcon("/gutter/overridenMethod.png");

  private final Project myProject;
  private final PsiFile myFile;
  private final Document myDocument;
  private final int myStartOffset;
  private final int myEndOffset;

  private LineMarkerInfo[] myMarkers = LineMarkerInfo.EMPTY_ARRAY;

  private Map<PsiClass,PsiClass> myClassToFirstDerivedMap = new THashMap<PsiClass, PsiClass>();

  public OverridenMarkersPass(
    Project project,
    PsiFile file,
    Document document,
    int startOffset,
    int endOffset
    ) {
    super(document);
    myProject = project;
    myFile = file;
    myDocument = document;
    myStartOffset = startOffset;
    myEndOffset = endOffset;
  }

  public void doCollectInformation(ProgressIndicator progress) {
    final PsiElement[] psiRoots = myFile.getPsiRoots();
    for (int i = 0; i < psiRoots.length; i++) {
      final PsiElement psiRoot = psiRoots[i];
      PsiElement[] elements = CodeInsightUtil.getElementsInRange(psiRoot, myStartOffset, myEndOffset);
      myMarkers = collectLineMarkers(elements);
    }
  }

  public void doApplyInformationToEditor() {
    UpdateHighlightersUtil.setLineMarkersToEditor(
      myProject, myDocument, myStartOffset, myEndOffset,
      myMarkers, UpdateHighlightersUtil.OVERRIDEN_MARKERS_GROUP);

    DaemonCodeAnalyzerImpl daemonCodeAnalyzer = (DaemonCodeAnalyzerImpl)DaemonCodeAnalyzer.getInstance(myProject);
    daemonCodeAnalyzer.getFileStatusMap().markFileUpToDate(myDocument, FileStatusMap.OVERRIDEN_MARKERS);
  }

  public int getPassId() {
    return Pass.UPDATE_OVERRIDEN_MARKERS;
  }

  private LineMarkerInfo[] collectLineMarkers(PsiElement[] elements) throws ProcessCanceledException {
    ApplicationManager.getApplication().assertReadAccessAllowed();

    List<LineMarkerInfo> array = new ArrayList<LineMarkerInfo>();
    for(int i = 0; i < elements.length; i++){
      ProgressManager.getInstance().checkCanceled();
      addLineMarkerInfo(elements[i], array);
    }
    return array.toArray(new LineMarkerInfo[array.size()]);
  }

  private void addLineMarkerInfo(PsiElement element, List<LineMarkerInfo> result) {
    if (element instanceof PsiIdentifier) {
      final PsiManager manager = PsiManager.getInstance(myProject);
      PsiSearchHelper helper = manager.getSearchHelper();
      if (element.getParent() instanceof PsiMethod) {
        collectOverridingMethods(element, helper, manager, result);
      }
      else if (element.getParent() instanceof PsiClass && !(element.getParent() instanceof PsiTypeParameter)) {
        collectInheritingClasses(element, helper, result);
      }
      else if (element.getParent() instanceof PsiField) {
        collectBoundForms(element, helper, result);
      }
    }
  }

  private void collectBoundForms(PsiElement element, PsiSearchHelper helper, List<LineMarkerInfo> result) {
    PsiField field = (PsiField)element.getParent();
    PsiClass aClass = field.getContainingClass();
    if (aClass != null && aClass.getQualifiedName() != null) {
      PsiFile[] formFiles = helper.findFormsBoundToClass(aClass.getQualifiedName());
      filesLoop:
      for (int i = 0; i < formFiles.length; i++) {
        PsiFile file = formFiles[i];
        final PsiReference[] references = file.getReferences();
        for (int j = 0; j < references.length; j++) {
          final PsiReference reference = references[j];
          if(reference.isReferenceTo(field)){
            int offset = element.getTextRange().getStartOffset();
            result.add(new LineMarkerInfo(LineMarkerInfo.BOUND_CLASS_OR_FIELD, field, offset, Icons.UI_FORM_ICON));
            break filesLoop;
          }
        }
      }
    }
  }

  private void collectInheritingClasses(PsiElement element, PsiSearchHelper helper, List<LineMarkerInfo> result) {
    PsiClass aClass = (PsiClass) element.getParent();
    if (aClass.getNameIdentifier().equals(element)) {
      if (!aClass.hasModifierProperty(PsiModifier.FINAL)) {
        PsiElementProcessor.CollectElements<PsiClass> processor = new PsiElementProcessor.CollectElements<PsiClass>();
        helper.processInheritors(processor, aClass, GlobalSearchScope.projectScope(myProject), false);
        Collection<PsiClass> inheritors = processor.getCollection();
        if (!inheritors.isEmpty()) {
          if (!myClassToFirstDerivedMap.containsKey(aClass)){
            myClassToFirstDerivedMap.put(aClass, inheritors.toArray(PsiClass.EMPTY_ARRAY)[0]);
          }
          int offset = element.getTextRange().getStartOffset();
          LineMarkerInfo info = new LineMarkerInfo(LineMarkerInfo.SUBCLASSED_CLASS, aClass, offset, aClass.isInterface() ? IMPLEMENTED_INTERFACE_MARKER_RENDERER : SUBCLASSED_CLASS_MARKER_RENDERER);

          result.add(info);
        }
      }

      if (aClass.getQualifiedName() != null) {
        ProgressManager.getInstance().checkCanceled();
        if (helper.findFormsBoundToClass(aClass.getQualifiedName()).length > 0) {
          int offset = element.getTextRange().getStartOffset();
          result.add(new LineMarkerInfo(LineMarkerInfo.BOUND_CLASS_OR_FIELD, aClass, offset, Icons.UI_FORM_ICON));
        }
      }
    }
  }

  private void collectOverridingMethods(PsiElement element, PsiSearchHelper helper, final PsiManager manager, List<LineMarkerInfo> result) {
    PsiMethod method = (PsiMethod)element.getParent();
    if (method.getNameIdentifier().equals(element)){
      if (!PsiUtil.canBeOverriden(method)) return;

      PsiClass parentClass = method.getContainingClass();
      if (!myClassToFirstDerivedMap.containsKey(parentClass)){
        PsiElementProcessor.FindElement<PsiClass> processor = new PsiElementProcessor.FindElement<PsiClass>();
        helper.processInheritors(processor, parentClass, GlobalSearchScope.projectScope(myProject), false);
        PsiClass derived = processor.getFoundElement();
        myClassToFirstDerivedMap.put(parentClass, derived);
      }

      PsiClass derived = myClassToFirstDerivedMap.get(parentClass);
      if (derived == null) return;

      PsiSubstitutor substitutor = TypeConversionUtil.getSuperClassSubstitutor(parentClass, derived, PsiSubstitutor.EMPTY);
      if (substitutor == null) substitutor = PsiSubstitutor.EMPTY;
      MethodSignature signature = method.getSignature(substitutor);
      PsiMethod method1 = MethodSignatureUtil.findMethodBySignature(derived, signature, false);
      if (method1 != null) {
        if (method1.hasModifierProperty(PsiModifier.STATIC) ||
           (method.hasModifierProperty(PsiModifier.PACKAGE_LOCAL) && !manager.arePackagesTheSame(parentClass, derived))) {
          method1 = null;
        }
      }
      boolean found = method1 != null;

      if (!found){
        PsiElementProcessor.FindElement<PsiMethod> processor = new PsiElementProcessor.FindElement<PsiMethod>();
        helper.processOverridingMethods(processor, method, GlobalSearchScope.projectScope(myProject), true);
        found = processor.isFound();
      }

      if (found) {
        boolean overrides;
        overrides = !method.hasModifierProperty(PsiModifier.ABSTRACT);

        int offset = method.getNameIdentifier().getTextRange().getStartOffset();
        LineMarkerInfo info = new LineMarkerInfo(LineMarkerInfo.OVERRIDEN_METHOD, method, offset, overrides ? OVERRIDEN_METHOD_MARKER_RENDERER : IMPLEMENTED_METHOD_MARKER_RENDERER);

        result.add(info);
      }
    }
  }
}