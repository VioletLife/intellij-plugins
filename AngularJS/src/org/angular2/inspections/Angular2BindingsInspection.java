// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.inspections;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.MultiMap;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.util.XmlTagUtil;
import org.angular2.codeInsight.Angular2DeclarationsScope;
import org.angular2.codeInsight.Angular2DeclarationsScope.DeclarationProximity;
import org.angular2.codeInsight.attributes.Angular2ApplicableDirectivesProvider;
import org.angular2.codeInsight.attributes.Angular2AttributeDescriptor;
import org.angular2.codeInsight.tags.Angular2TagDescriptor;
import org.angular2.entities.Angular2Directive;
import org.angular2.inspections.quickfixes.Angular2FixesFactory;
import org.angular2.inspections.quickfixes.RemoveAttributeQuickFix;
import org.angular2.lang.expr.psi.Angular2TemplateBinding;
import org.angular2.lang.expr.psi.Angular2TemplateBindings;
import org.angular2.lang.html.parser.Angular2AttributeNameParser.AttributeInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.angular2.codeInsight.Angular2DeclarationsScope.DeclarationProximity.IN_SCOPE;
import static org.angular2.codeInsight.Angular2DeclarationsScope.DeclarationProximity.NOT_REACHABLE;
import static org.angular2.codeInsight.Angular2Processor.isTemplateTag;
import static org.angular2.codeInsight.tags.Angular2TagDescriptorsProvider.NG_SPECIAL_TAGS;
import static org.angular2.lang.html.parser.Angular2AttributeType.*;

public class Angular2BindingsInspection extends Angular2HtmlLikeTemplateLocalInspectionTool {

  @Override
  protected void visitAngularAttribute(@NotNull ProblemsHolder holder,
                                       @NotNull XmlAttribute attribute,
                                       @NotNull Angular2AttributeDescriptor descriptor) {
    AttributeInfo info = descriptor.getInfo();
    boolean templateTag = isTemplateTag(attribute.getParent().getName());
    if (info.type == TEMPLATE_BINDINGS) {
      if (templateTag) {
        return;
      }
      visitTemplateBindings(holder, attribute, Angular2TemplateBindings.get(attribute));
      return;
    }
    else if (info.type == REFERENCE || info.type == VARIABLE) {
      return;
    }
    List<Angular2Directive> sourceDirectives = descriptor.getSourceDirectives();
    Angular2DeclarationsScope scope = new Angular2DeclarationsScope(attribute);
    DeclarationProximity proximity;
    if (sourceDirectives == null) {
      if (templateTag) {
        proximity = NOT_REACHABLE;
      }
      else {
        return;
      }
    }
    else {
      proximity = scope.getDeclarationsProximity(sourceDirectives);
    }
    if (proximity == IN_SCOPE) {
      return;
    }
    List<LocalQuickFix> quickFixes = new SmartList<>();
    if (proximity != NOT_REACHABLE) {
      Angular2FixesFactory.addUnresolvedDeclarationFixes(attribute, quickFixes);
    }
    quickFixes.add(new RemoveAttributeQuickFix(attribute.getName()));
    String message;
    ProblemHighlightType severity;
    // TODO take into account 'CUSTOM_ELEMENTS_SCHEMA' and 'NO_ERRORS_SCHEMA' value of '@NgModule.schemas'
    severity = (info.type != EVENT || templateTag) && scope.isFullyResolved()
               ? ProblemHighlightType.GENERIC_ERROR_OR_WARNING
               : ProblemHighlightType.WEAK_WARNING;
    switch (info.type) {
      case EVENT:
        if (templateTag) {
          message = "Event '" + info.name + "' is not emitted by any applicable directive on an embedded template.";
        }
        else {
          message = "Event '" + info.name + "' is not emitted by any applicable directives nor by '"
                    + attribute.getParent().getName() + "' element.";
        }
        break;
      case PROPERTY_BINDING:
        if (templateTag) {
          message =
            "Property '" + info.name + "' is not provided by any applicable directive on an embedded template.";
        }
        else {
          message =
            "Property '" + info.name + "' is not provided by any applicable directives nor by '" +
            attribute.getParent().getName() +
            "' element.";
        }
        break;
      case BANANA_BOX_BINDING:
        message = "Can't bind to '" + attribute.getName() + "' since it is not provided by any applicable directives.";
        break;
      case REGULAR:
        message = "Directive providing attribute " + info.name + " is out of the current module's scope.";
        break;
      default:
        return;
    }
    holder.registerProblem(attribute.getNameElement(), message, severity,
                           quickFixes.toArray(LocalQuickFix.EMPTY_ARRAY));
  }

  @Override
  protected void visitXmlTag(@NotNull ProblemsHolder holder, @NotNull XmlTag tag) {
    XmlElementDescriptor descriptor = tag.getDescriptor();
    if (!(descriptor instanceof Angular2TagDescriptor) || NG_SPECIAL_TAGS.contains(descriptor.getName())) {
      return;
    }
    Angular2DeclarationsScope scope = new Angular2DeclarationsScope(tag);
    Angular2ApplicableDirectivesProvider provider = new Angular2ApplicableDirectivesProvider(tag, true);
    DeclarationProximity proximity = scope.getDeclarationsProximity(provider.getMatched());
    if (proximity == IN_SCOPE) {
      return;
    }
    XmlToken tagName = XmlTagUtil.getStartTagNameElement(tag);
    if (tagName == null) {
      return;
    }
    List<LocalQuickFix> quickFixes = new SmartList<>();
    if (proximity != NOT_REACHABLE) {
      Angular2FixesFactory.addUnresolvedDeclarationFixes(tag, quickFixes);
    }
    holder.registerProblem(tagName,
                           "Component or directive matching " + tagName.getText() + " element is out of the current module's scope",
                           scope.isFullyResolved() ? ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                                                   : ProblemHighlightType.WEAK_WARNING,
                           quickFixes.toArray(LocalQuickFix.EMPTY_ARRAY));
  }

  private static void visitTemplateBindings(@NotNull ProblemsHolder holder,
                                            @NotNull XmlAttribute attribute,
                                            @NotNull Angular2TemplateBindings bindings) {
    List<Angular2Directive> matched = new Angular2ApplicableDirectivesProvider(bindings).getMatched();
    Angular2DeclarationsScope scope = new Angular2DeclarationsScope(attribute);
    DeclarationProximity proximity = scope.getDeclarationsProximity(matched);
    if (proximity != IN_SCOPE) {
      List<LocalQuickFix> fixes = new SmartList<>();
      Angular2FixesFactory.addUnresolvedDeclarationFixes(bindings, fixes);
      holder.registerProblem(attribute.getNameElement(),
                             "No directive is matched on attribute '" + bindings.getTemplateName() + "'.",
                             ProblemHighlightType.WEAK_WARNING,
                             fixes.toArray(LocalQuickFix.EMPTY_ARRAY));
      return;
    }

    MultiMap<String, Angular2Directive> input2DirectiveMap = new MultiMap<>();
    matched.forEach(
      dir -> dir.getInputs().forEach(
        input -> input2DirectiveMap.putValue(input.getName(), dir)));

    for (Angular2TemplateBinding binding : bindings.getBindings()) {
      if (!binding.keyIsVar() && binding.getExpression() != null) {
        proximity = scope.getDeclarationsProximity(input2DirectiveMap.get(binding.getKey()));
        if (proximity != IN_SCOPE) {
          PsiElement element = ObjectUtils.notNull(binding.getKeyElement(), attribute.getNameElement());
          List<LocalQuickFix> fixes = new SmartList<>();
          Angular2FixesFactory.addUnresolvedDeclarationFixes(binding, fixes);
          holder.registerProblem(element, "Property '" + binding.getKey() +
                                          "' is not provided by any applicable directive on an embedded template.",
                                 scope.isFullyResolved() ? ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                                                         : ProblemHighlightType.WEAK_WARNING,
                                 fixes.toArray(LocalQuickFix.EMPTY_ARRAY));
        }
      }
    }
  }
}
