// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.daemon.impl.focusMode;

import com.intellij.codeHighlighting.EditorBoundHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.lang.LanguageExtension;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.FocusModeModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Segment;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.TimeoutUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FocusModePassFactory implements TextEditorHighlightingPassFactory {
  private static final LanguageExtension<FocusModeProvider> EP_NAME = new LanguageExtension<>("com.intellij.focusModeProvider");
  private static final long MAX_ALLOWED_TIME = 100;
  private static final Logger LOG = Logger.getInstance(FocusModePassFactory.class);

  public FocusModePassFactory(@NotNull TextEditorHighlightingPassRegistrar highlightingPassRegistrar) {
    highlightingPassRegistrar.registerTextEditorHighlightingPass(this, null, null, false, -1);
  }

  @Override
  @Nullable
  public TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
    return isEnabled() && EditorUtil.isRealFileEditor(editor) && editor instanceof EditorImpl
           ? new FocusModePass(editor, file)
           : null;
  }

  private static boolean isEnabled() {
    return Registry.is("editor.focus.mode");
  }

  @Nullable
  public static List<? extends Segment> calcFocusZones(@Nullable PsiFile file) {
    if (file == null || !isEnabled()) return null;
    return CachedValuesManager.getCachedValue(file, () -> {
      FileViewProvider provider = file.getViewProvider();

      List<Segment> segments = EP_NAME.allForLanguageOrAny(provider.getBaseLanguage()).stream()
        .map(p -> calcFocusZones(p, file))
        .map(l -> ContainerUtil.append(l, file.getTextRange()))
        .findFirst().orElse(null);
      return CachedValueProvider.Result.create(segments, file);
    });
  }

  /**
   * Computes focus zones for the {@code psiFile} using {@code focusModeProvider}. Additionally warns, if zones building took too long
   * (longer than {@link #MAX_ALLOWED_TIME} ms.
   */
  @NotNull
  private static List<? extends Segment> calcFocusZones(@NotNull FocusModeProvider focusModeProvider, @NotNull PsiFile psiFile) {
    Ref<List<? extends Segment>> resultRef = Ref.create();
    long executionTime = TimeoutUtil.measureExecutionTime(() -> resultRef.set(focusModeProvider.calcFocusZones(psiFile)));
    if (executionTime > MAX_ALLOWED_TIME) {
      LOG.warn("Focus zones collecting took too long: " + executionTime + "ms; " +
               "Provider: " + focusModeProvider.getClass().getSimpleName() + "; " +
               "File size: " + psiFile.getTextLength() + "; " +
               "Ranges collected: " + resultRef.get().size());
    }
    return resultRef.get();
  }

  public static void setToEditor(@NotNull List<? extends Segment> zones, Editor editor) {
    Document document = editor.getDocument();
    List<RangeMarker> before = editor.getUserData(FocusModeModel.FOCUS_MODE_RANGES);
    if (before != null) {
      before.forEach(o -> o.dispose());
    }

    List<RangeMarker> rangeMarkers = ContainerUtil.map(zones, z -> document.createRangeMarker(z.getStartOffset(), z.getEndOffset()));
    editor.putUserData(FocusModeModel.FOCUS_MODE_RANGES, rangeMarkers);
  }

  private static class FocusModePass extends EditorBoundHighlightingPass {
    private List<? extends Segment> myZones;

    private FocusModePass(Editor editor, PsiFile file) {
      super(editor, file, false);
    }

    @Override
    public void doCollectInformation(@NotNull ProgressIndicator progress) {
      myZones = calcFocusZones(myFile);
    }

    @Override
    public void doApplyInformationToEditor() {
      if (myZones != null) {
        setToEditor(myZones, myEditor);
        ((EditorImpl)myEditor).applyFocusMode();
      }
    }
  }
}
