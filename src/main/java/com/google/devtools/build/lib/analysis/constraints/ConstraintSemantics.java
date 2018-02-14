// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.constraints;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.LabelAndLocation;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.EnvironmentWithGroup;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.LabelClass;
import com.google.devtools.build.lib.syntax.Type.LabelVisitor;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Implementation of the semantics of Bazel's constraint specification and enforcement system.
 *
 * <p>This is how the system works:
 *
 * <p>All build rules can declare which "static environments" they can be built for, where a
 * "static environment" is a label instance of an {@link EnvironmentRule} rule declared in a
 * BUILD file. There are various ways to do this:
 *
 * <ul>
 *   <li>Through a "restricted to" attribute setting
 *   ({@link RuleClass#RESTRICTED_ENVIRONMENT_ATTR}). This is the most direct form of
 *   specification - it declares the exact set of environments the rule supports (for its group -
 *   see precise details below).
 *   <li>Through a "compatible with" attribute setting
 *   ({@link RuleClass#COMPATIBLE_ENVIRONMENT_ATTR}. This declares <b>additional</b>
 *   environments a rule supports in addition to "standard" environments that are supported by
 *   default (see below).
 *   <li>Through "default" specifications in {@link EnvironmentGroup} rules. Every environment
 *   belongs to a group of thematically related peers (e.g. "target architectures", "JDK versions",
 *   or "mobile devices"). An environment group's definition includes which of these
 *   environments should be supported "by default" if not otherwise specified by one of the above
 *   mechanisms. In particular, a rule with no environment-related attributes automatically
 *   inherits all defaults.
 *   <li>Through a rule class default ({@link RuleClass.Builder#restrictedTo} and
 *   {@link RuleClass.Builder#compatibleWith}). This overrides global defaults for all instances
 *   of the given rule class. This can be used, for example, to make all *_test rules "testable"
 *   without each instance having to explicitly declare this capability.
 * </ul>
 *
 * <p>Groups exist to model the idea that some environments are related while others have nothing
 * to do with each other. Say, for example, we want to say a rule works for PowerPC platforms but
 * not x86. We can do so by setting its "restricted to" attribute to
 * {@code ['//sample/path:powerpc']}. Because both PowerPC and x86 are in the same
 * "target architectures" group, this setting removes x86 from the set of supported environments.
 * But since JDK support belongs to its own group ("JDK versions") it says nothing about which JDK
 * the rule supports.
 *
 * <p>More precisely, if a rule has a "restricted to" value of [A, B, C], this removes support
 * for all default environments D such that group(D) is in [group(A), group(B), group(C)] AND
 * D is not in [A, B, C] (in other words, D isn't explicitly opted back in). The rule's full
 * set of supported environments thus becomes [A, B, C] + all defaults that belong to unrelated
 * groups.
 *
 * <p>If the rule has a "compatible with" value of [E, F, G], these are unconditionally
 * added to its set of supported environments (in addition to the results from above).
 *
 * <p>An environment may not appear in both a rule's "restricted to" and "compatible with" values.
 * If two environments belong to the same group, they must either both be in "restricted to",
 * both be in "compatible with", or not explicitly specified.
 *
 * <p>Given all the above, constraint enforcement is this: rule A can depend on rule B if, for
 * every static environment A supports, B also supports that environment.
 *
 * <p>Configurable attributes introduce the additional concept of "refined environments". Given:
 *
 * <pre>
 *   java_library(
 *       name = "lib",
 *       restricted_to = [":A", ":B"],
 *       deps = select({
 *           ":config_a": [":depA"],
 *           ":config_b": [":depB"],
 *       }))
 *   java_library(
 *       name = "depA",
 *       restricted_to = [":A"])
 *   java_library(
 *       name = "depB",
 *       restricted_to = [":B"])
 * </pre>
 *
 * "lib"'s static environments are what are declared via restricted_to: {@code [":A", ":B"]}.
 * But normal constraint checking doesn't work well here: neither "depA" or "depB" supports both
 * environments, so each is technically invalid. But the two of them together <i>do</i> support
 * both environments. So constraint checking with selects checks that "lib"'s environments
 * are supported by the <i>union</i> of its selectable dependencies, then <i>refines</i> its
 * environments to whichever deps get chosen. In other words:
 *
 * <ol>
 *   <li>The above example is considered constraint-valid.
 *   <li>When building with "config_a", "lib"'s refined environment set is {@code [":A"]}.
 *   <li>When building with "config_b", "lib"'s refined environment set is {@code [":B"]}.
 *   <li>Any rule depending on "lib" has its environments refined by the intersection with "lib".
 *       So if "depender" has {@code restricted_to = [":A", ":B"]} and {@code deps = [":lib"]},
 *       then when building with "config_a", "depender"'s refined environment set is {@code [":A"]}.
 *   <li>For each environment group, every rule's refined environment set must be non-empty. This
 *       ensures the "chosen" dep in a select matches all rules up the dependency chain. So if
 *       "depender" had {@code restricted_to = [":B"]}, it wouldn't be allowed in a "config_a"
 *       build.
 * </ol>
 * </code>.
 */
public class ConstraintSemantics {
  private ConstraintSemantics() {
  }

  /**
   * Provides a set of default environments for a given environment group.
   */
  private interface DefaultsProvider {
    Collection<Label> getDefaults(EnvironmentGroup group);
  }

  /**
   * Provides a group's defaults as specified in the environment group's BUILD declaration.
   */
  private static class GroupDefaultsProvider implements DefaultsProvider {
    @Override
    public Collection<Label> getDefaults(EnvironmentGroup group) {
      return group.getDefaults();
    }
  }

  /**
   * Provides a group's defaults, factoring in rule class defaults as specified by
   * {@link com.google.devtools.build.lib.packages.RuleClass.Builder#compatibleWith}
   * and {@link com.google.devtools.build.lib.packages.RuleClass.Builder#restrictedTo}.
   */
  private static class RuleClassDefaultsProvider implements DefaultsProvider {
    private final EnvironmentCollection ruleClassDefaults;
    private final GroupDefaultsProvider groupDefaults;

    RuleClassDefaultsProvider(EnvironmentCollection ruleClassDefaults) {
      this.ruleClassDefaults = ruleClassDefaults;
      this.groupDefaults = new GroupDefaultsProvider();
    }

    @Override
    public Collection<Label> getDefaults(EnvironmentGroup group) {
      if (ruleClassDefaults.getGroups().contains(group)) {
        return ruleClassDefaults.getEnvironments(group);
      } else {
        // If there are no rule class defaults for this group, just inherit global defaults.
        return groupDefaults.getDefaults(group);
      }
    }
  }

  /**
   * Collects the set of supported environments for a given rule by merging its
   * restriction-style and compatibility-style environment declarations as specified by
   * the given attributes. Only includes environments from "known" groups, i.e. the groups
   * owning the environments explicitly referenced from these attributes.
   */
  private static class EnvironmentCollector {
    private final RuleContext ruleContext;
    private final String restrictionAttr;
    private final String compatibilityAttr;
    private final DefaultsProvider defaultsProvider;

    private final EnvironmentCollection restrictionEnvironments;
    private final EnvironmentCollection compatibilityEnvironments;
    private final EnvironmentCollection supportedEnvironments;

    /**
     * Constructs a new collector on the given attributes.
     *
     * @param ruleContext analysis context for the rule
     * @param restrictionAttr the name of the attribute that declares "restricted to"-style
     *     environments. If the rule doesn't have this attribute, this is considered an
     *     empty declaration.
     * @param compatibilityAttr the name of the attribute that declares "compatible with"-style
     *     environments. If the rule doesn't have this attribute, this is considered an
     *     empty declaration.
     * @param defaultsProvider provider for the default environments within a group if not
     *     otherwise overridden by the above attributes
     */
    EnvironmentCollector(RuleContext ruleContext, String restrictionAttr, String compatibilityAttr,
        DefaultsProvider defaultsProvider) {
      this.ruleContext = ruleContext;
      this.restrictionAttr = restrictionAttr;
      this.compatibilityAttr = compatibilityAttr;
      this.defaultsProvider = defaultsProvider;

      EnvironmentCollection.Builder environmentsBuilder = new EnvironmentCollection.Builder();
      restrictionEnvironments = collectRestrictionEnvironments(environmentsBuilder);
      compatibilityEnvironments = collectCompatibilityEnvironments(environmentsBuilder);
      supportedEnvironments = environmentsBuilder.build();
    }

    /**
     * Returns the set of environments supported by this rule, as determined by the
     * restriction-style attribute, compatibility-style attribute, and group defaults
     * provider instantiated with this class.
     */
    EnvironmentCollection getEnvironments() {
      return supportedEnvironments;
    }

    /**
     * Validity-checks that no group has its environment referenced in both the "compatible with"
     * and restricted to" attributes. Returns true if all is good, returns false and reports
     * appropriate errors if there are any problems.
     */
    boolean validateEnvironmentSpecifications() {
      ImmutableCollection<EnvironmentGroup> restrictionGroups = restrictionEnvironments.getGroups();
      boolean hasErrors = false;

      for (EnvironmentGroup group : compatibilityEnvironments.getGroups()) {
        if (restrictionGroups.contains(group)) {
          // To avoid error-spamming the user, when we find a conflict we only report one example
          // environment from each attribute for that group.
          Label compatibilityEnv =
              compatibilityEnvironments.getEnvironments(group).iterator().next();
          Label restrictionEnv = restrictionEnvironments.getEnvironments(group).iterator().next();

          if (compatibilityEnv.equals(restrictionEnv)) {
            ruleContext.attributeError(compatibilityAttr, compatibilityEnv
                + " cannot appear both here and in " + restrictionAttr);
          } else {
            ruleContext.attributeError(compatibilityAttr, compatibilityEnv + " and "
                + restrictionEnv + " belong to the same environment group. They should be declared "
                + "together either here or in " + restrictionAttr);
          }
          hasErrors = true;
        }
      }

      return !hasErrors;
    }

    /**
     * Adds environments specified in the "restricted to" attribute to the set of supported
     * environments and returns the environments added.
     */
    private EnvironmentCollection collectRestrictionEnvironments(
        EnvironmentCollection.Builder supportedEnvironments) {
      return collectEnvironments(restrictionAttr, supportedEnvironments);
    }

    /**
     * Adds environments specified in the "compatible with" attribute to the set of supported
     * environments, along with all defaults from the groups they belong to. Returns these
     * environments, not including the defaults.
     */
    private EnvironmentCollection collectCompatibilityEnvironments(
        EnvironmentCollection.Builder supportedEnvironments) {
      EnvironmentCollection compatibilityEnvironments =
          collectEnvironments(compatibilityAttr, supportedEnvironments);
      for (EnvironmentGroup group : compatibilityEnvironments.getGroups()) {
        supportedEnvironments.putAll(group, defaultsProvider.getDefaults(group));
      }
      return compatibilityEnvironments;
    }

    /**
     * Adds environments specified by the given attribute to the set of supported environments
     * and returns the environments added.
     *
     * <p>If this rule doesn't have the given attributes, returns an empty set.
     */
    private EnvironmentCollection collectEnvironments(String attrName,
        EnvironmentCollection.Builder supportedEnvironments) {
      if (!ruleContext.getRule().isAttrDefined(attrName,  BuildType.LABEL_LIST)) {
        return EnvironmentCollection.EMPTY;
      }
      EnvironmentCollection.Builder environments = new EnvironmentCollection.Builder();
      for (TransitiveInfoCollection envTarget :
          ruleContext.getPrerequisites(attrName, RuleConfiguredTarget.Mode.DONT_CHECK)) {
        EnvironmentWithGroup envInfo = resolveEnvironment(envTarget);
        environments.put(envInfo.group(), envInfo.environment());
        supportedEnvironments.put(envInfo.group(), envInfo.environment());
      }
      return environments.build();
    }

    /**
     * Returns the environment and its group. An {@link Environment} rule only "supports" one
     * environment: itself. Extract that from its more generic provider interface and sanity
     * check that that's in fact what we see.
     */
    private static EnvironmentWithGroup resolveEnvironment(TransitiveInfoCollection envRule) {
      SupportedEnvironmentsProvider prereq =
          Preconditions.checkNotNull(envRule.getProvider(SupportedEnvironmentsProvider.class));
      return Iterables.getOnlyElement(prereq.getStaticEnvironments().getGroupedEnvironments());
    }
  }

  /**
   * Exception indicating errors finding/parsing environments or their containing groups.
   */
  public static class EnvironmentLookupException extends Exception {
    private EnvironmentLookupException(String message) {
      super(message);
    }
  }

  /**
   * Returns the environment group that owns the given environment. Both must belong to
   * the same package.
   *
   * @throws EnvironmentLookupException if the input is not an {@link EnvironmentRule} or no
   *     matching group is found
   */
  public static EnvironmentGroup getEnvironmentGroup(Target envTarget)
      throws EnvironmentLookupException {
    if (!(envTarget instanceof Rule)
        || !((Rule) envTarget).getRuleClass().equals(EnvironmentRule.RULE_NAME)) {
      throw new EnvironmentLookupException(
          envTarget.getLabel() + " is not a valid environment definition");
    }
    for (EnvironmentGroup group : envTarget.getPackage().getTargets(EnvironmentGroup.class)) {
      if (group.getEnvironments().contains(envTarget.getLabel())) {
        return group;
      }
    }
    throw new EnvironmentLookupException(
        "cannot find the group for environment " + envTarget.getLabel());
  }

  /**
   * Returns the set of environments this rule supports, applying the logic described in
   * {@link ConstraintSemantics}.
   *
   * <p>Note this set is <b>not complete</b> - it doesn't include environments from groups we don't
   * "know about". Environments and groups can be declared in any package. If the rule includes
   * no references to that package, then it simply doesn't know anything about them. But the
   * constraint semantics say the rule should support the defaults for that group. We encode this
   * implicitly: given the returned set, for any group that's not in the set the rule is also
   * considered to support that group's defaults.
   *
   * @param ruleContext analysis context for the rule. A rule error is triggered here if
   *     invalid constraint settings are discovered.
   * @return the environments this rule supports, not counting defaults "unknown" to this rule
   *     as described above. Returns null if any errors are encountered.
   */
  @Nullable
  public static EnvironmentCollection getSupportedEnvironments(RuleContext ruleContext) {
    if (!validateAttributes(ruleContext)) {
      return null;
    }

    // This rule's rule class defaults (or null if the rule class has no defaults).
    EnvironmentCollector ruleClassCollector = maybeGetRuleClassDefaults(ruleContext);
    // Default environments for this rule. If the rule has rule class defaults, this is
    // those defaults. Otherwise it's the global defaults specified by environment_group
    // declarations.
    DefaultsProvider ruleDefaults;

    if (ruleClassCollector != null) {
      if (!ruleClassCollector.validateEnvironmentSpecifications()) {
        return null;
      }
      ruleDefaults = new RuleClassDefaultsProvider(ruleClassCollector.getEnvironments());
    } else {
      ruleDefaults = new GroupDefaultsProvider();
    }

    EnvironmentCollector ruleCollector = new EnvironmentCollector(ruleContext,
        RuleClass.RESTRICTED_ENVIRONMENT_ATTR, RuleClass.COMPATIBLE_ENVIRONMENT_ATTR, ruleDefaults);
    if (!ruleCollector.validateEnvironmentSpecifications()) {
      return null;
    }

    EnvironmentCollection supportedEnvironments = ruleCollector.getEnvironments();
    if (ruleClassCollector != null) {
      // If we have rule class defaults from groups that aren't referenced from the rule itself,
      // we need to add them in too to override the global defaults.
      supportedEnvironments =
          addUnknownGroupsToCollection(supportedEnvironments, ruleClassCollector.getEnvironments());
    }
    return supportedEnvironments;
  }

  /**
   * Returns the rule class defaults specified for this rule, or null if there are
   * no such defaults.
   */
  @Nullable
  private static EnvironmentCollector maybeGetRuleClassDefaults(RuleContext ruleContext) {
    Rule rule = ruleContext.getRule();
    String restrictionAttr = RuleClass.DEFAULT_RESTRICTED_ENVIRONMENT_ATTR;
    String compatibilityAttr = RuleClass.DEFAULT_COMPATIBLE_ENVIRONMENT_ATTR;

    if (rule.isAttrDefined(restrictionAttr, BuildType.LABEL_LIST)
      || rule.isAttrDefined(compatibilityAttr, BuildType.LABEL_LIST)) {
      return new EnvironmentCollector(ruleContext, restrictionAttr, compatibilityAttr,
          new GroupDefaultsProvider());
    } else {
      return null;
    }
  }

  /**
   * Adds environments to an {@link EnvironmentCollection} from groups that aren't already
   * a part of that collection.
   *
   * @param environments the collection to add to
   * @param toAdd the collection to add. All environments in this collection in groups
   *     that aren't represented in {@code environments} are added to {@code environments}.
   * @return the expanded collection.
   */
  private static EnvironmentCollection addUnknownGroupsToCollection(
      EnvironmentCollection environments, EnvironmentCollection toAdd) {
    EnvironmentCollection.Builder builder = new EnvironmentCollection.Builder();
    builder.putAll(environments);
    for (EnvironmentGroup candidateGroup : toAdd.getGroups()) {
      if (!environments.getGroups().contains(candidateGroup)) {
        builder.putAll(candidateGroup, toAdd.getEnvironments(candidateGroup));
      }
    }
    return builder.build();
  }

  /**
   * Validity-checks this rule's constraint-related attributes. Returns true if all is good,
   * returns false and reports appropriate errors if there are any problems.
   */
  private static boolean validateAttributes(RuleContext ruleContext) {
    AttributeMap attributes = ruleContext.attributes();

    // Report an error if "restricted to" is explicitly set to nothing. Even if this made
    // conceptual sense, we don't know which groups we should apply that to.
    String restrictionAttr = RuleClass.RESTRICTED_ENVIRONMENT_ATTR;
    List<? extends TransitiveInfoCollection> restrictionEnvironments = ruleContext
        .getPrerequisites(restrictionAttr, RuleConfiguredTarget.Mode.DONT_CHECK);
    if (restrictionEnvironments.isEmpty()
        && attributes.isAttributeValueExplicitlySpecified(restrictionAttr)) {
      ruleContext.attributeError(restrictionAttr, "attribute cannot be empty");
      return false;
    }

    return true;
  }

  /**
   * Helper container for checkConstraints: stores both a set of deps that need to be
   * constraint-checked and the subset of those deps that only appear inside selects.
   */
  private static class DepsToCheck {
    private final Set<TransitiveInfoCollection> allDeps;
    private final Set<TransitiveInfoCollection> selectOnlyDeps;
    DepsToCheck(Set<TransitiveInfoCollection> depsToCheck,
        Set<TransitiveInfoCollection> selectOnlyDeps) {
      this.allDeps = depsToCheck;
      this.selectOnlyDeps = selectOnlyDeps;
    }
    Set<TransitiveInfoCollection> allDeps() {
      return allDeps;
    }
    boolean isSelectOnly(TransitiveInfoCollection dep) {
      return selectOnlyDeps.contains(dep);
    }
  }

  /**
   * Performs constraint checking on the given rule's dependencies and reports any errors. This
   * includes:
   *
   * <ul>
   *   <li>Static environment checking: if this rule supports environment E, all deps outside
   *       selects must also support E
   *   <li>Refined environment computation: this rule's refined environments are its static
   *       environments intersected with the refined environments of all dependencies (including
   *       chosen deps in selects)
   *   <li>Refined environment checking: no environment groups can be "emptied" due to refinement
   * </ul>
   *
   * @param ruleContext the rule to analyze
   * @param staticEnvironments the rule's supported environments, as defined by the return value of
   *     {@link #getSupportedEnvironments}. In particular, for any environment group that's not in
   *     this collection, the rule is assumed to support the defaults for that group.
   * @param refinedEnvironments a builder for populating this rule's refined environments
   * @param removedEnvironmentCulprits a builder for populating the core dependencies that trigger
   *     pruning away environments through refinement. If multiple dependencies qualify (e.g.
   */
  public static void checkConstraints(
      RuleContext ruleContext,
      EnvironmentCollection staticEnvironments,
      EnvironmentCollection.Builder refinedEnvironments,
      Map<Label, LabelAndLocation> removedEnvironmentCulprits) {
    Set<EnvironmentWithGroup> refinedEnvironmentsSoFar = new LinkedHashSet<>();
    // Start with the full set of static environments:
    refinedEnvironmentsSoFar.addAll(staticEnvironments.getGroupedEnvironments());
    Set<EnvironmentGroup> groupsWithEnvironmentsRemoved = new LinkedHashSet<>();
    // Maps the label results of getUnsupportedEnvironments() to EnvironmentWithGroups. We can't
    // have that method just return EnvironmentWithGroups because it also collects group defaults,
    // which we only have labels for.
    Map<Label, EnvironmentWithGroup> labelsToEnvironments = new HashMap<>();
    for (EnvironmentWithGroup envWithGroup : staticEnvironments.getGroupedEnvironments()) {
      labelsToEnvironments.put(envWithGroup.environment(), envWithGroup);
    }

    DepsToCheck depsToCheck = getConstraintCheckedDependencies(ruleContext);

    for (TransitiveInfoCollection dep : depsToCheck.allDeps()) {
      if (!depsToCheck.isSelectOnly(dep)) {
        // TODO(bazel-team): support static constraint checking for selects. A selectable constraint
        // is valid if the union of all deps in the select includes all of this rule's static
        // environments. Determining that requires following the select paths that don't get chosen,
        // which means we won't have ConfiguredTargets for those deps and need to find another
        // way to get their environments.
        checkStaticConstraints(ruleContext, staticEnvironments, dep);
      }
      refineEnvironmentsForDep(ruleContext, staticEnvironments, dep, labelsToEnvironments,
          refinedEnvironmentsSoFar, groupsWithEnvironmentsRemoved, removedEnvironmentCulprits);
    }

    checkRefinedConstraints(ruleContext, groupsWithEnvironmentsRemoved,
        refinedEnvironmentsSoFar, refinedEnvironments, removedEnvironmentCulprits);
  }

  /**
   * Performs static constraint checking against the given dep.
   *
   * @param ruleContext the rule being analyzed
   * @param staticEnvironments the static environments of the rule being analyzed
   * @param dep the dep to check
   */
  private static void checkStaticConstraints(RuleContext ruleContext,
      EnvironmentCollection staticEnvironments, TransitiveInfoCollection dep) {
    SupportedEnvironmentsProvider depEnvironments =
        dep.getProvider(SupportedEnvironmentsProvider.class);
    Collection<Label> unsupportedEnvironments =
        getUnsupportedEnvironments(depEnvironments.getStaticEnvironments(), staticEnvironments);
    if (!unsupportedEnvironments.isEmpty()) {
      ruleContext.ruleError("dependency " + dep.getLabel()
          + " doesn't support expected environment"
          + (unsupportedEnvironments.size() == 1 ? "" : "s")
          + ": " + Joiner.on(", ").join(unsupportedEnvironments));
    }
  }

  /**
   * Helper method for {@link #checkConstraints}: refines a rule's environments with the given dep.
   *
   * <p>A rule's <b>complete</b> refined set applies this process to every dep.
   */
  private static void refineEnvironmentsForDep(
      RuleContext ruleContext,
      EnvironmentCollection staticEnvironments,
      TransitiveInfoCollection dep,
      Map<Label, EnvironmentWithGroup> labelsToEnvironments,
      Set<EnvironmentWithGroup> refinedEnvironmentsSoFar,
      Set<EnvironmentGroup> groupsWithEnvironmentsRemoved,
      Map<Label, LabelAndLocation> removedEnvironmentCulprits) {

    SupportedEnvironmentsProvider depEnvironments =
        dep.getProvider(SupportedEnvironmentsProvider.class);

    // Stores the environments that are pruned from the refined set because of this dep. Even
    // though they're removed, some subset of the environments they fulfill may belong in the
    // refined set. For example, if environment "both" fulfills "a" and "b" and "lib" statically
    // sets restricted_to = ["both"] and "dep" sets restricted_to = ["a"], then lib's refined set
    // excludes "both". But rather than be emptied out it can be reduced to "a".
    Set<Label> prunedEnvironmentsFromThisDep = new LinkedHashSet<>();

    // Refine this rule's environments by intersecting with the dep's refined environments:
    for (Label refinedEnvironmentToPrune : getUnsupportedEnvironments(
        depEnvironments.getRefinedEnvironments(), staticEnvironments)) {
      EnvironmentWithGroup envToPrune = labelsToEnvironments.get(refinedEnvironmentToPrune);
      if (envToPrune == null) {
        // If we have no record of this environment, that means the current rule implicitly uses
        // the defaults for this group. So explicitly opt that group's defaults into the refined
        // set before trying to remove specific items.
        for (EnvironmentWithGroup defaultEnv :
            getDefaults(refinedEnvironmentToPrune, depEnvironments.getRefinedEnvironments())) {
          refinedEnvironmentsSoFar.add(defaultEnv);
          labelsToEnvironments.put(defaultEnv.environment(), defaultEnv);
        }
        envToPrune = Verify.verifyNotNull(labelsToEnvironments.get(refinedEnvironmentToPrune));
      }
      refinedEnvironmentsSoFar.remove(envToPrune);
      groupsWithEnvironmentsRemoved.add(envToPrune.group());
      removedEnvironmentCulprits.put(envToPrune.environment(),
          findOriginalRefiner(ruleContext, depEnvironments, envToPrune));
      prunedEnvironmentsFromThisDep.add(envToPrune.environment());
    }

    // Add in any dep environment that one of the environments we removed fulfills. In other
    // words, the removed environment is no good, but some subset of it may be.
    for (EnvironmentWithGroup depEnv :
        depEnvironments.getRefinedEnvironments().getGroupedEnvironments()) {
      for (Label fulfiller : depEnv.group().getFulfillers(depEnv.environment())) {
        if (prunedEnvironmentsFromThisDep.contains(fulfiller)) {
          refinedEnvironmentsSoFar.add(depEnv);
        }
      }
    }
  }

  /**
   * Helper method for {@link #checkConstraints}: performs refined environment constraint checking.
   *
   * <p>Refined environment expectations: no environment group should be emptied out due to
   * refining. This reflects the idea that some of the static declared environments get pruned out
   * by the build configuration, but <i>all</i> environments shouldn't be pruned out.
   *
   * <p>Violations of this expectation trigger rule analysis errors.
   */
  private static void checkRefinedConstraints(
      RuleContext ruleContext,
      Set<EnvironmentGroup> groupsWithEnvironmentsRemoved,
      Set<EnvironmentWithGroup> refinedEnvironmentsSoFar,
      EnvironmentCollection.Builder refinedEnvironments,
      Map<Label, LabelAndLocation> removedEnvironmentCulprits) {
    Set<EnvironmentGroup> refinedGroups = new LinkedHashSet<>();
    for (EnvironmentWithGroup envWithGroup : refinedEnvironmentsSoFar) {
      refinedEnvironments.put(envWithGroup.group(), envWithGroup.environment());
      refinedGroups.add(envWithGroup.group());
    }
    Set<EnvironmentGroup> newlyEmptyGroups = groupsWithEnvironmentsRemoved.isEmpty()
        ? ImmutableSet.<EnvironmentGroup>of()
        : Sets.difference(groupsWithEnvironmentsRemoved, refinedGroups);
    if (!newlyEmptyGroups.isEmpty()) {
      ruleContext.ruleError(getOverRefinementError(newlyEmptyGroups, removedEnvironmentCulprits));
    }
  }

  /**
   * Constructs an error message for when all environments have been pruned out of one or more
   * environment groups due to refining.
   */
  private static String getOverRefinementError(
      Set<EnvironmentGroup> newlyEmptyGroups,
      Map<Label, LabelAndLocation> removedEnvironmentCulprits) {
    StringBuilder message = new StringBuilder("the current command-line flags disqualify "
        + "all supported environments because of incompatible select() paths:");
    for (EnvironmentGroup group : newlyEmptyGroups) {
      if (newlyEmptyGroups.size() > 1) {
        message.append("\n\nenvironment group: " + group.getLabel() + ":");
      }
      for (Label prunedEnvironment : group.getEnvironments()) {
        LabelAndLocation culprit = removedEnvironmentCulprits.get(prunedEnvironment);
        if (culprit != null) { // Only environments this rule declared support for have culprits.
          message.append("\n environment: " + prunedEnvironment
              + " removed by: " + culprit.getLabel() + " (" + culprit.getLocation() + ")");
        }
      }
    }
    return message.toString();
  }

  /**
   * Given an environment that should be refined out of the current rule because of the given dep,
   * returns the original dep that caused the removal.
   *
   * <p>For example, say we have R -> D1 -> D2 and all rules support environment E. If the
   * refinement happens because D2 has
   *
   * <pre>
   *   deps = select({":foo": ["restricted_to_E"], ":bar": ["restricted_to_F"]}}  # Choose F.
   * </pre>
   *
   * <p>then D2 is the original refiner (even though D1 and R inherit the same pruning).
   */
  private static LabelAndLocation findOriginalRefiner(
      RuleContext ruleContext, SupportedEnvironmentsProvider dep, EnvironmentWithGroup envToPrune) {
    LabelAndLocation depCulprit = dep.getRemovedEnvironmentCulprit(envToPrune.environment());
    // If the dep has no record of this environment being refined, that means the current rule
    // is the culprit.
    return depCulprit == null ? LabelAndLocation.of(ruleContext.getTarget()) : depCulprit;
  }

  /**
   * Finds the given environment in the given set and returns the default environments for its
   * group.
   */
  private static Collection<EnvironmentWithGroup> getDefaults(Label env,
      EnvironmentCollection allEnvironments) {
    EnvironmentGroup group = null;
    for (EnvironmentGroup candidateGroup : allEnvironments.getGroups()) {
      if (candidateGroup.getDefaults().contains(env)) {
        group = candidateGroup;
        break;
      }
    }
    Verify.verifyNotNull(group);
    ImmutableSet.Builder<EnvironmentWithGroup> builder = ImmutableSet.builder();
    for (Label defaultEnv : group.getDefaults()) {
      builder.add(EnvironmentWithGroup.create(defaultEnv, group));
    }
    return builder.build();
  }

  /**
   * Given a collection of environments and a collection of expected environments, returns the
   * missing environments that would cause constraint expectations to be violated. Includes
   * the effects of environment group defaults.
   */
  public static Collection<Label> getUnsupportedEnvironments(
      EnvironmentCollection actualEnvironments, EnvironmentCollection expectedEnvironments) {
    Set<Label> missingEnvironments = new LinkedHashSet<>();
    Collection<Label> actualEnvironmentLabels = actualEnvironments.getEnvironments();

    // Check if each explicitly expected environment is satisfied.
    for (EnvironmentWithGroup expectedEnv : expectedEnvironments.getGroupedEnvironments()) {
      EnvironmentGroup group = expectedEnv.group();
      Label environment = expectedEnv.environment();
      boolean isSatisfied = false;
      if (actualEnvironments.getGroups().contains(group)) {
        // If the actual environments include members from the expected environment's group, we
        // need to either find the environment itself or another one that transitively fulfills it.
        if (actualEnvironmentLabels.contains(environment)
            || intersect(actualEnvironmentLabels, group.getFulfillers(environment))) {
          isSatisfied = true;
        }
      } else {
        // If the actual environments don't reference the expected environment's group at all,
        // the group's defaults are implicitly included. So we need to check those defaults for
        // either the expected environment or another environment that transitively fulfills it.
        if (group.isDefault(environment)
            || intersect(group.getFulfillers(environment), group.getDefaults())) {
          isSatisfied = true;
        }
      }
      if (!isSatisfied) {
        missingEnvironments.add(environment);
      }
    }

    // For any environment group not referenced by the expected environments, its defaults are
    // implicitly expected. We can ignore this if the actual environments also don't reference the
    // group (since in that case the same defaults apply), otherwise we have to check.
    for (EnvironmentGroup group : actualEnvironments.getGroups()) {
      if (!expectedEnvironments.getGroups().contains(group)) {
        for (Label expectedDefault : group.getDefaults()) {
          if (!actualEnvironmentLabels.contains(expectedDefault)
              && !intersect(actualEnvironmentLabels, group.getFulfillers(expectedDefault))) {
            missingEnvironments.add(expectedDefault);
          }
        }
      }
    }

    return missingEnvironments;
  }

  private static boolean intersect(Iterable<Label> labels1, Iterable<Label> labels2) {
    return !Sets.intersection(Sets.newHashSet(labels1), Sets.newHashSet(labels2)).isEmpty();
  }

  /**
   * Returns all dependencies that should be constraint-checked against the current rule,
   * including both "uncoditional" deps (outside selects) and deps that only appear in selects.
   */
  private static DepsToCheck getConstraintCheckedDependencies(RuleContext ruleContext) {
    Set<TransitiveInfoCollection> depsToCheck = new LinkedHashSet<>();
    Set<TransitiveInfoCollection> selectOnlyDeps = new LinkedHashSet<>();
    Set<TransitiveInfoCollection> depsOutsideSelects = new LinkedHashSet<>();

    AttributeMap attributes = ruleContext.attributes();
    for (String attr : attributes.getAttributeNames()) {
      Attribute attrDef = attributes.getAttributeDefinition(attr);
      if (attrDef.getType().getLabelClass() != LabelClass.DEPENDENCY
          || attrDef.skipConstraintsOverride()) {
        continue;
      }
      if (!attrDef.checkConstraintsOverride()) {
        // Use the same implicit deps check that query uses. This facilitates running queries to
        // determine exactly which rules need to be constraint-annotated for depot migrations.
        if (!DependencyFilter.NO_IMPLICIT_DEPS.apply(ruleContext.getRule(), attrDef)
            // We can't identify host deps by calling BuildConfiguration.isHostConfiguration()
            // because --nodistinct_host_configuration subverts that call.
            || attrDef.getConfigurationTransition().isHostTransition()) {
          continue;
        }
      }

      Set<Label> selectOnlyDepsForThisAttribute =
          getDepsOnlyInSelects(ruleContext, attr, attributes.getAttributeType(attr));
      for (TransitiveInfoCollection dep :
          ruleContext.getPrerequisites(attr, RuleConfiguredTarget.Mode.DONT_CHECK)) {
        // Output files inherit the environment spec of their generating rule.
        if (dep instanceof OutputFileConfiguredTarget) {
          // Note this reassignment means constraint violation errors reference the generating
          // rule, not the file. This makes the source of the environmental mismatch more clear.
          dep = ((OutputFileConfiguredTarget) dep).getGeneratingRule();
        }
        // Input files don't support environments. We may subsequently opt them into constraint
        // checking, but for now just pass them by.
        if (dep.getProvider(SupportedEnvironmentsProvider.class) != null) {
          depsToCheck.add(dep);
          if (!selectOnlyDepsForThisAttribute.contains(dep.getLabel())) {
            depsOutsideSelects.add(dep);
          }
        }
      }
    }

    for (TransitiveInfoCollection dep : depsToCheck) {
      if (!depsOutsideSelects.contains(dep)) {
        selectOnlyDeps.add(dep);
      }
    }

    return new DepsToCheck(depsToCheck, selectOnlyDeps);
  }

  /**
   * Returns the deps for this attribute that only appear in selects.
   *
   * <p>For example:
   * <pre>
   *     deps = [":a"] + select({"//foo:cond": [":b"]}) + select({"//conditions:default": [":c"]})
   * </pre>
   *
   * returns {@code [":b"]}. Even though {@code [":c"]} also appears in a select, that's a
   * degenerate case with only one always-chosen condition. So that's considered the same as
   * an unconditional dep.
   *
   * <p>Note that just because a dep only appears in selects for this attribute doesn't mean it
   * won't appear unconditionally in another attribute.
   */
  private static Set<Label> getDepsOnlyInSelects(RuleContext ruleContext, String attr,
      Type<?> attrType) {
    Rule rule = ruleContext.getRule();
    if (!rule.isConfigurableAttribute(attr) || !BuildType.isLabelType(attrType)) {
      return ImmutableSet.of();
    }
    Set<Label> unconditionalDeps = new LinkedHashSet<>();
    Set<Label> selectableDeps = new LinkedHashSet<>();
    BuildType.SelectorList<?> selectList = (BuildType.SelectorList<?>)
        RawAttributeMapper.of(rule).getRawAttributeValue(rule, attr);
    for (BuildType.Selector<?> select : selectList.getSelectors()) {
      addSelectValuesToSet(select, select.isUnconditional() ? unconditionalDeps : selectableDeps);
    }
    return Sets.difference(selectableDeps, unconditionalDeps);
  }

  /**
   * Adds all label values from the given select to the given set. Automatically handles different
   * value types (e.g. labels vs. label lists).
   */
  private static void addSelectValuesToSet(BuildType.Selector<?> select, final Set<Label> set) {
    Type<?> type = select.getOriginalType();
    LabelVisitor<?> visitor = new LabelVisitor<Object>() {
      @Override
      public void visit(Label label, Object dummy) {
        set.add(label);
      }
    };
    for (Object value : select.getEntries().values()) {
      try {
        type.visitLabels(visitor, value, /*context=*/ null);
      } catch (InterruptedException ex) {
        // Because the LabelVisitor does not throw InterruptedException, it should not be thrown
        // by visitLabels here.
        throw new AssertionError(ex);
      }
    }
  }
}
