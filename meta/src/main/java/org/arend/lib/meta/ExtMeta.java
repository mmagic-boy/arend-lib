package org.arend.lib.meta;

import org.arend.ext.FreeBindingsModifier;
import org.arend.ext.concrete.*;
import org.arend.ext.concrete.expr.ConcreteArgument;
import org.arend.ext.concrete.expr.ConcreteCaseArgument;
import org.arend.ext.concrete.expr.ConcreteExpression;
import org.arend.ext.core.context.CoreBinding;
import org.arend.ext.core.context.CoreParameter;
import org.arend.ext.core.expr.*;
import org.arend.ext.core.ops.NormalizationMode;
import org.arend.ext.core.ops.SubstitutionPair;
import org.arend.ext.error.*;
import org.arend.ext.reference.ArendRef;
import org.arend.ext.typechecking.*;
import org.arend.lib.StdExtension;
import org.arend.lib.error.TypeError;
import org.arend.lib.util.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ExtMeta extends BaseMetaDefinition {
  private final StdExtension ext;

  public ExtMeta(StdExtension ext) {
    this.ext = ext;
  }

  @Override
  public boolean @Nullable [] argumentExplicitness() {
    return new boolean[] { true };
  }

  @Override
  public int numberOfOptionalExplicitArguments() {
    return 1;
  }

  @Override
  public boolean requireExpectedType() {
    return true;
  }

  private static class PiTree {
    public final ConcreteExpression head;
    public final ConcreteExpression altHead;
    public final List<Integer> indices;
    public final List<PiTree> subtrees;

    private PiTree(ConcreteExpression head, ConcreteExpression altHead, List<Integer> indices, List<PiTree> subtrees) {
      this.head = head;
      this.altHead = altHead;
      this.indices = indices;
      this.subtrees = subtrees;
    }
  }

  private class PiTreeMaker {
    private final ExpressionTypechecker typechecker;
    private final ConcreteFactory factory;
    private final List<ConcreteLetClause> clauses;
    private List<ConcreteParameter> lamParams;
    private List<SubstitutionPair> substitution;
    private int index = 1;

    private PiTreeMaker(ExpressionTypechecker typechecker, ConcreteFactory factory, List<ConcreteLetClause> clauses) {
      this.typechecker = typechecker;
      this.factory = factory;
      this.clauses = clauses;
    }

    private PiTree make(CoreExpression expr) {
      List<CoreParameter> params = new ArrayList<>();
      CoreExpression codomain = expr.getPiParameters(params);
      Set<? extends CoreBinding> freeVars = codomain.findFreeBindings();

      for (CoreParameter param : params) {
        if (freeVars.contains(param.getBinding())) {
          freeVars.clear();
          params.clear();
          codomain = expr;
          break;
        }
      }

      ConcreteExpression concrete;
      List<Integer> indices;
      if (freeVars.isEmpty()) {
        concrete = factory.core(codomain.computeTyped());
        indices = Collections.emptyList();
      } else {
        indices = new ArrayList<>(freeVars.size());
        for (int i = 0; i < substitution.size(); i++) {
          if (freeVars.contains(substitution.get(i).binding)) {
            indices.add(i);
          }
        }

        List<ConcreteParameter> redLamParams;
        List<SubstitutionPair> redSubstitution;
        if (indices.size() == substitution.size()) {
          redLamParams = lamParams;
          redSubstitution = substitution;
        } else {
          redLamParams = new ArrayList<>(indices.size());
          redSubstitution = new ArrayList<>(indices.size());
          for (Integer index : indices) {
            redLamParams.add(lamParams.get(index));
            redSubstitution.add(substitution.get(index));
          }
        }

        CoreExpression finalCodomain = codomain;
        TypedExpression result = typechecker.typecheck(factory.lam(redLamParams, factory.meta("ext_sigma_pi_param", new MetaDefinition() {
          @Override
          public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
            CoreExpression result = typechecker.substitute(finalCodomain, null, redSubstitution);
            return result == null ? null : result.computeTyped();
          }
        })), null);
        if (result == null) return null;
        concrete = factory.core(result);
      }

      ArendRef letRef = factory.local("T" + index++);
      clauses.add(factory.letClause(letRef, Collections.emptyList(), null, concrete));

      List<PiTree> subtrees = new ArrayList<>(params.size());
      for (CoreParameter param : params) {
        PiTree subtree = make(param.getTypeExpr());
        if (subtree == null) return null;
        subtrees.add(subtree);
      }
      return new PiTree(concrete, factory.ref(letRef), indices, subtrees);
    }

    private PiTree make(CoreExpression expr, List<CoreParameter> parameters) {
      lamParams = new ArrayList<>(parameters.size());
      substitution = new ArrayList<>(parameters.size());
      for (int i = 0; i < parameters.size(); i++) {
        CoreParameter parameter = parameters.get(i);
        ArendRef ref = factory.local("x" + (i + 1));
        lamParams.add(factory.param(true, Collections.singletonList(ref), factory.core(parameter.getTypedType())));
        substitution.add(new SubstitutionPair(parameter.getBinding(), factory.ref(ref)));
      }
      return make(expr);
    }


    private ConcreteExpression makeConcrete(PiTree tree, boolean useLet, List<ConcreteExpression> args) {
      return makeConcrete(tree, useLet, args, args, true);
    }

    private ConcreteExpression makeConcrete(PiTree tree, boolean useLet, List<ConcreteExpression> evenArgs, List<ConcreteExpression> oddArgs, boolean isEven) {
      ConcreteExpression result = useLet ? tree.altHead : tree.head;
      if (!tree.indices.isEmpty()) {
        List<ConcreteExpression> headArgs = new ArrayList<>(tree.indices.size());
        for (Integer index : tree.indices) {
          headArgs.add((isEven ? evenArgs : oddArgs).get(index));
        }
        result = factory.app(useLet ? tree.altHead : tree.head, true, headArgs);
      }

      for (int i = tree.subtrees.size() - 1; i >= 0; i--) {
        result = factory.arr(makeConcrete(tree.subtrees.get(i), useLet, evenArgs, oddArgs, !isEven), result);
      }
      return result;
    }

    private ConcreteExpression makeCoe(PiTree tree, boolean useHead, boolean useLet, List<ConcreteExpression> pathRefs, ConcreteExpression arg) {
      ArendRef coeRef = factory.local("i");
      ConcreteExpression coeLam = factory.lam(Collections.singletonList(factory.param(coeRef)), factory.meta("ext_coe", new MetaDefinition() {
        @Override
        public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
          List<ConcreteExpression> args = new ArrayList<>();
          for (ConcreteExpression pathRef : pathRefs) {
            args.add(factory.app(factory.ref(ext.prelude.getAt().getRef()), true, Arrays.asList(pathRef, factory.ref(coeRef))));
          }
          return typechecker.typecheck(useHead ? factory.app(useLet ? tree.altHead : tree.head, true, args) : makeConcrete(tree, useLet, args), null);
        }
      }));
      return factory.app(factory.ref(ext.prelude.getCoerce().getRef()), true, Arrays.asList(coeLam, arg, factory.ref(ext.prelude.getRight().getRef())));
    }

    private ConcreteExpression etaExpand(PiTree tree, ConcreteExpression fun, List<ConcreteExpression> args, boolean insertCoe, boolean useLet, List<ConcreteExpression> pathRefs) {
      List<ConcreteExpression> expandedArgs = new ArrayList<>(args.size());
      for (int i = 0; i < args.size(); i++) {
        PiTree subtree = tree.subtrees.get(i);
        List<ConcreteParameter> lamParams = new ArrayList<>(subtree.subtrees.size());
        List<ConcreteExpression> lamRefs = new ArrayList<>(subtree.subtrees.size());
        for (int j = 0; j < subtree.subtrees.size(); j++) {
          ArendRef lamRef = factory.local("x" + index++);
          lamParams.add(factory.param(lamRef));
          lamRefs.add(factory.ref(lamRef));
        }
        expandedArgs.add(factory.lam(lamParams, etaExpand(subtree, args.get(i), lamRefs, !insertCoe, useLet, pathRefs)));
      }

      ConcreteExpression result = factory.app(fun, true, expandedArgs);
      if (!insertCoe || tree.indices.isEmpty()) {
        return result;
      }

      if (tree.indices.size() == 1) {
        return factory.app(factory.ref(ext.transport.getRef()), true, Arrays.asList(useLet ? tree.altHead : tree.head, pathRefs.get(tree.indices.get(0)), result));
      }

      return makeCoe(tree, true, useLet, pathRefs, result);
    }

    private ConcreteExpression makeArgType(PiTree tree, boolean useLet, List<ConcreteExpression> leftRefs, List<ConcreteExpression> rightRefs, List<ConcreteExpression> pathRefs, ConcreteExpression leftFun, ConcreteExpression rightFun) {
      List<ConcreteExpression> piRefs = new ArrayList<>(tree.subtrees.size());
      List<ConcreteParameter> piParams = new ArrayList<>(tree.subtrees.size());
      for (int i = 0; i < tree.subtrees.size(); i++) {
        ArendRef piRef = factory.local("s" + (i + 1));
        piRefs.add(factory.ref(piRef));
        piParams.add(factory.param(true, Collections.singletonList(piRef), makeConcrete(tree.subtrees.get(i), useLet, leftRefs, rightRefs, true)));
      }

      index = 1;
      ConcreteExpression leftArg = etaExpand(tree, leftFun, piRefs, true, useLet, pathRefs);
      index = 1;
      ConcreteExpression rightArg = etaExpand(tree, rightFun, piRefs, false, useLet, pathRefs);
      return factory.pi(piParams, factory.app(factory.ref(ext.prelude.getEquality().getRef()), true, Arrays.asList(leftArg, rightArg)));
    }
  }

  private static class PiTreeData {
    private final PiTreeMaker maker;
    private final PiTree tree;
    private final List<ConcreteExpression> leftProjs;

    private PiTreeData(PiTreeMaker maker, PiTree tree, List<ConcreteExpression> leftProjs) {
      this.maker = maker;
      this.tree = tree;
      this.leftProjs = leftProjs;
    }
  }

  private static boolean useLet(CoreExpression expr, int index) {
    if (expr instanceof CoreReferenceExpression) {
      return false;
    }
    if (!(expr instanceof CoreTupleExpression)) {
      return true;
    }
    CoreTupleExpression tuple = (CoreTupleExpression) expr;
    return !(index < tuple.getFields().size() && tuple.getFields().get(index) instanceof CoreReferenceExpression);
  }

  private class ExtGenerator {
    private final ExpressionTypechecker typechecker;
    private final ConcreteFactory factory;
    private final ConcreteSourceNode marker;
    private final ArendRef iRef;

    private ExtGenerator(ExpressionTypechecker typechecker, ConcreteFactory factory, ConcreteSourceNode marker, ArendRef iRef) {
      this.typechecker = typechecker;
      this.factory = factory;
      this.marker = marker;
      this.iRef = iRef;
    }

    private ConcreteExpression applyAt(ConcreteExpression arg) {
      return factory.app(factory.ref(ext.prelude.getAt().getRef()), true, Arrays.asList(arg, factory.ref(iRef)));
    }

    private TypedExpression hidingIRef(ConcreteExpression expr, CoreExpression type) {
      return typechecker.withFreeBindings(new FreeBindingsModifier().remove(typechecker.getFreeBinding(iRef)), tc -> tc.typecheck(expr, type));
    }

    private ConcreteExpression makeCoeLambda(CoreSigmaExpression sigma, CoreBinding paramBinding, Set<CoreBinding> used, Map<CoreBinding, ConcreteExpression> sigmaRefs, ConcreteFactory factory) {
      ArendRef coeRef = factory.local("i");
      return factory.lam(Collections.singletonList(factory.param(coeRef)), factory.meta("ext_coe", new MetaDefinition() {
        @Override
        public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
          List<SubstitutionPair> substitution = new ArrayList<>();
          for (CoreParameter param = sigma.getParameters(); param.getBinding() != paramBinding; param = param.getNext()) {
            if (used == null || used.contains(param.getBinding())) {
              substitution.add(new SubstitutionPair(param.getBinding(), factory.app(factory.ref(ext.prelude.getAt().getRef()), true, Arrays.asList(sigmaRefs.get(param.getBinding()), factory.ref(coeRef)))));
            }
          }
          CoreExpression result = typechecker.substitute(paramBinding.getTypeExpr(), null, substitution);
          return result == null ? null : result.computeTyped();
        }
      }));
    }

    private ConcreteExpression generate(ConcreteExpression arg, CoreExpression type, CoreExpression coreLeft, CoreExpression coreRight) {
      ConcreteExpression left = factory.core(coreLeft.computeTyped());
      ConcreteExpression right = factory.core(coreRight.computeTyped());

      if (type instanceof CorePiExpression) {
        List<CoreParameter> piParams = new ArrayList<>();
        type.getPiParameters(piParams);
        List<ConcreteParameter> concretePiParams = new ArrayList<>();
        List<ConcreteParameter> concreteLamParams = new ArrayList<>();
        List<ConcreteArgument> args = new ArrayList<>();
        List<SubstitutionPair> substitution = new ArrayList<>();
        for (int i = 0; i < piParams.size(); i++) {
          CoreParameter piParam = piParams.get(i);
          ArendRef ref = factory.local(ext.renamerFactory.getNameFromBinding(piParam.getBinding(), null));
          int finalI = i;
          concretePiParams.add(factory.param(piParam.isExplicit(), Collections.singletonList(ref), factory.meta("ext_param", new MetaDefinition() {
            @Override
            public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
              CoreExpression paramType = typechecker.substitute(piParam.getTypeExpr(), null, substitution.subList(0, finalI));
              return paramType == null ? null : paramType.computeTyped();
            }
          })));
          concreteLamParams.add(factory.param(piParam.isExplicit(), ref));
          ConcreteExpression refExpr = factory.ref(ref);
          args.add(factory.arg(refExpr, piParam.isExplicit()));
          substitution.add(new SubstitutionPair(piParam.getBinding(), refExpr));
        }

        TypedExpression piEqType = typechecker.typecheck(factory.pi(concretePiParams, factory.app(factory.ref(ext.prelude.getEquality().getRef()), true, Arrays.asList(factory.app(left, args), factory.app(right, args)))), null);
        if (piEqType == null) return null;
        TypedExpression result = hidingIRef(arg, piEqType.getExpression());
        return result == null ? null : factory.lam(concreteLamParams, applyAt(factory.app(factory.core(result), args)));
      }

      if (type instanceof CoreSigmaExpression) {
        CoreSigmaExpression sigma = (CoreSigmaExpression) type;
        Set<CoreBinding> bindings = new HashSet<>();
        Set<CoreBinding> dependentBindings = new HashSet<>();
        List<ConcreteParameter> sigmaParams = new ArrayList<>();
        Map<CoreBinding, ConcreteExpression> sigmaRefs = new HashMap<>();
        ConcreteExpression lastSigmaParam = null;
        Set<CoreBinding> propBindings = new HashSet<>();
        Set<CoreBinding> totalUsed = new HashSet<>();
        List<Set<CoreBinding>> usedList = new ArrayList<>();
        List<ConcreteLetClause> letClauses = new ArrayList<>();
        List<PiTreeData> piTreeDataList = new ArrayList<>();
        int i = 0;
        for (CoreParameter param = sigma.getParameters(); param.hasNext(); param = param.getNext(), i++) {
          Set<CoreBinding> used = new HashSet<>();
          CoreBinding paramBinding = param.getBinding();
          boolean isProp = isProp(paramBinding.getTypeExpr());
          if (isProp) {
            propBindings.add(paramBinding);
          }
          if (!bindings.isEmpty()) {
            if (param.getTypeExpr().processSubexpression(e -> {
              if (!(e instanceof CoreReferenceExpression)) {
                return CoreExpression.FindAction.CONTINUE;
              }
              CoreBinding binding = ((CoreReferenceExpression) e).getBinding();
              if (bindings.contains(binding)) {
                if (!isProp) {
                  if (dependentBindings.contains(binding)) {
                    return CoreExpression.FindAction.STOP;
                  }
                  dependentBindings.add(paramBinding);
                }
                used.add(binding);
              }
              return CoreExpression.FindAction.CONTINUE;
            })) {
              typechecker.getErrorReporter().report(new TypecheckingError("\\Sigma types with more than two level of dependencies are not supported", marker));
              return null;
            }
          }
          bindings.add(paramBinding);
          totalUsed.addAll(used);
          usedList.add(used);

          ArendRef sigmaRef = factory.local("p" + (i + 1));
          sigmaRefs.put(paramBinding, factory.ref(sigmaRef));
          PiTreeData piTreeData = null;
          if (!isProp) {
            boolean isPi = false;
            ConcreteExpression leftExpr = factory.proj(left, i);
            if (dependentBindings.contains(paramBinding)) {
              CoreExpression paramType = param.getTypeExpr().normalize(NormalizationMode.WHNF);
              if (paramType instanceof CorePiExpression) {
                List<CoreParameter> sigmaParameters = new ArrayList<>();
                List<ConcreteExpression> leftProjs = new ArrayList<>();
                List<ConcreteExpression> rightProjs = new ArrayList<>();
                List<ConcreteExpression> pathRefs = new ArrayList<>();
                int j = 0;
                for (CoreParameter parameter = sigma.getParameters(); parameter != param; parameter = parameter.getNext(), j++) {
                  if (used.contains(parameter.getBinding())) {
                    sigmaParameters.add(parameter);
                    leftProjs.add(factory.proj(left, j));
                    rightProjs.add(factory.proj(right, j));
                    pathRefs.add(sigmaRefs.get(parameter.getBinding()));
                  }
                }

                PiTreeMaker piTreeMaker = new PiTreeMaker(typechecker, factory, letClauses);
                PiTree piTree = piTreeMaker.make(paramType, sigmaParameters);
                if (piTree == null) return null;
                if (!piTree.subtrees.isEmpty()) {
                  piTreeData = new PiTreeData(piTreeMaker, piTree, leftProjs);
                  lastSigmaParam = piTreeMaker.makeArgType(piTreeData.tree, false, leftProjs, rightProjs, pathRefs, factory.proj(left, j), factory.proj(right, j));
                  isPi = true;
                }
              }

              if (!isPi) {
                if (used.size() > 1) {
                  leftExpr = factory.app(factory.ref(ext.prelude.getCoerce().getRef()), true, Arrays.asList(makeCoeLambda(sigma, paramBinding, used, sigmaRefs, factory), leftExpr, factory.ref(ext.prelude.getRight().getRef())));
                } else {
                  CoreBinding binding = used.iterator().next();
                  ArendRef transportRef = factory.local(ext.renamerFactory.getNameFromBinding(binding, null));
                  leftExpr = factory.app(factory.ref(ext.transport.getRef()), true, Arrays.asList(factory.lam(Collections.singletonList(factory.param(transportRef)), factory.meta("ext_transport", new MetaDefinition() {
                    @Override
                    public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
                      CoreExpression result = typechecker.substitute(paramBinding.getTypeExpr(), null, Collections.singletonList(new SubstitutionPair(binding, factory.ref(transportRef))));
                      return result == null ? null : result.computeTyped();
                    }
                  })), sigmaRefs.get(binding), leftExpr));
                }
              }
            }

            if (!isPi) {
              lastSigmaParam = factory.app(factory.ref(ext.prelude.getEquality().getRef()), true, Arrays.asList(leftExpr, factory.proj(right, i)));
            }
            sigmaParams.add(factory.param(true, Collections.singletonList(sigmaRef), lastSigmaParam));
          }
          piTreeDataList.add(piTreeData);
        }

        TypedExpression sigmaEqType = typechecker.typecheck(sigmaParams.size() == 1 ? lastSigmaParam : factory.sigma(sigmaParams), null);
        if (sigmaEqType == null) return null;
        TypedExpression result = hidingIRef(arg, sigmaEqType.getExpression());
        if (result == null) return null;

        ArendRef letRef;
        ConcreteExpression concreteTuple;
        CoreExpression resultExpr = result.getExpression().getUnderlyingExpression();
        if (resultExpr instanceof CoreReferenceExpression) {
          letRef = null;
          concreteTuple = factory.core(result);
        } else {
          letRef = factory.local("arg");
          concreteTuple = factory.ref(letRef);
        }

        if (letRef != null) {
          letClauses.add(factory.letClause(letRef, Collections.emptyList(), null, factory.core(result)));
        }

        List<ConcreteExpression> fields = new ArrayList<>();
        Map<CoreBinding, ConcreteExpression> fieldsMap = new HashMap<>();
        i = 0;
        for (CoreParameter param = sigma.getParameters(); param.hasNext(); param = param.getNext(), i++) {
          ConcreteExpression field;
          CoreBinding paramBinding = param.getBinding();
          boolean useLet;
          if (propBindings.contains(paramBinding)) {
            field = factory.app(factory.ref(ext.pathInProp.getRef()), true, Arrays.asList(makeCoeLambda(sigma, paramBinding, usedList.get(i), fieldsMap, factory), factory.hole(), factory.hole()));
            useLet = true;
          } else {
            ConcreteExpression proj = sigmaParams.size() == 1 ? concreteTuple : factory.proj(concreteTuple, i);
            if (piTreeDataList.get(i) != null) {
              if (!(coreLeft instanceof CoreReferenceExpression)) {
                ArendRef projRef = factory.local("l");
                letClauses.add(factory.letClause(projRef, Collections.emptyList(), null, left));
                left = factory.ref(projRef);
              }

              List<ConcreteExpression> rightRefs = new ArrayList<>();
              List<ConcreteExpression> pathRefs = new ArrayList<>();
              List<ConcreteCaseArgument> caseArgs = new ArrayList<>();
              List<ConcretePattern> casePatterns = new ArrayList<>();
              ArendRef lastCaseRef = factory.local("a");
              int j = 0;
              for (CoreParameter parameter = sigma.getParameters(); parameter != param; parameter = parameter.getNext(), j++) {
                if (!usedList.get(i).contains(parameter.getBinding())) {
                  continue;
                }

                ArendRef rightRef = factory.local("r" + (j + 1));
                rightRefs.add(factory.ref(rightRef));
                caseArgs.add(factory.caseArg(factory.proj(right, j), rightRef, null));

                ArendRef pathRef = factory.local("q" + (j + 1));
                pathRefs.add(factory.ref(pathRef));
                caseArgs.add(factory.caseArg(factory.proj(concreteTuple, j), pathRef, factory.app(factory.ref(ext.prelude.getEquality().getRef()), true, Arrays.asList(factory.proj(left, j), factory.ref(rightRef)))));

                casePatterns.add(factory.refPattern(null, null));
                casePatterns.add(factory.conPattern(ext.prelude.getIdp().getRef()));
              }

              PiTreeMaker piTreeMaker = piTreeDataList.get(i).maker;
              PiTree piTree = piTreeDataList.get(i).tree;
              ArendRef rightFunRef = factory.local("f");
              caseArgs.add(factory.caseArg(factory.proj(right, j), rightFunRef, piTreeMaker.makeConcrete(piTree, true, rightRefs)));
              caseArgs.add(factory.caseArg(factory.proj(concreteTuple, j), null, piTreeMaker.makeArgType(piTree, true, piTreeDataList.get(i).leftProjs, rightRefs, pathRefs, factory.proj(left, j), factory.ref(rightFunRef))));

              casePatterns.add(factory.refPattern(null, null));
              casePatterns.add(factory.refPattern(lastCaseRef, null));

              ConcreteExpression caseResultType = factory.app(factory.ref(ext.prelude.getEquality().getRef()), true, Arrays.asList(piTreeMaker.makeCoe(piTree, false, true, pathRefs, factory.proj(left, j)), factory.ref(rightFunRef)));
              proj = factory.caseExpr(false, caseArgs, caseResultType, null, factory.clause(casePatterns, factory.app(factory.meta("ext", ExtMeta.this), true, Collections.singletonList(factory.ref(lastCaseRef)))));
            }

            boolean isDependent = dependentBindings.contains(paramBinding);
            field = isDependent ? factory.app(factory.ref(ext.pathOver.getRef()), true, Collections.singletonList(proj)) : proj;
            if (isDependent) {
              useLet = true;
            } else {
              if (sigmaParams.size() == 1) {
                useLet = !(resultExpr instanceof CoreReferenceExpression);
              } else {
                useLet = useLet(resultExpr, i);
              }
            }
          }
          if (useLet && totalUsed.contains(paramBinding)) {
            ArendRef argLetRef = factory.local("h" + (i + 1));
            letClauses.add(factory.letClause(argLetRef, Collections.emptyList(), null, field));
            field = factory.ref(argLetRef);
          }
          fields.add(applyAt(field));
          fieldsMap.put(paramBinding, field);
        }

        ConcreteExpression concreteResult = factory.tuple(fields);
        return letClauses.isEmpty() ? concreteResult : factory.letExpr(false, letClauses, concreteResult);
      }

      typechecker.getErrorReporter().report(new TypeError("Cannot apply extensionality", type, marker));
      return null;
    }
  }

  private static boolean isProp(CoreExpression type) {
    CoreExpression typeType = type.computeType().normalize(NormalizationMode.WHNF);
    return typeType instanceof CoreUniverseExpression && ((CoreUniverseExpression) typeType).getSort().isProp();
  }

  @Override
  public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
    ConcreteSourceNode marker = contextData.getMarker();
    ErrorReporter errorReporter = typechecker.getErrorReporter();
    CoreFunCallExpression equality = Utils.toEquality(contextData.getExpectedType(), errorReporter, marker);
    if (equality == null) return null;

    List<? extends ConcreteArgument> args = contextData.getArguments();
    ConcreteFactory factory = ext.factory.withData(marker);
    CoreExpression type = equality.getDefCallArguments().get(0);
    if (isProp(type)) {
      if (!args.isEmpty()) {
        errorReporter.report(new IgnoredArgumentError(args.get(0).getExpression()));
      }
      return typechecker.typecheck(factory.app(factory.ref(ext.prelude.getInProp().getRef()), true, Arrays.asList(factory.hole(), factory.hole())), contextData.getExpectedType());
    }

    if (args.isEmpty()) {
      errorReporter.report(new MissingArgumentsError(1, marker));
      return null;
    }

    CoreExpression normType = type.normalize(NormalizationMode.WHNF);
    ConcreteExpression arg = args.get(0).getExpression();
    if (normType instanceof CoreUniverseExpression) {
      ConcreteExpression left = factory.core(equality.getDefCallArguments().get(1).computeTyped());
      ConcreteExpression right = factory.core(equality.getDefCallArguments().get(2).computeTyped());
      if (((CoreUniverseExpression) normType).getSort().isProp()) {
        TypedExpression expectedType = typechecker.typecheck(factory.sigma(Arrays.asList(factory.param(true, factory.arr(left, right)), factory.param(true, factory.arr(right, left)))), null);
        if (expectedType == null) return null;
        TypedExpression typedArg = typechecker.typecheck(arg, expectedType.getExpression());
        if (typedArg == null) return null;
        CoreExpression coreArg = typedArg.getExpression().getUnderlyingExpression();
        ConcreteExpression concreteArg = factory.core(typedArg);
        ArendRef letRef;
        ConcreteExpression concreteResult;
        if (coreArg instanceof CoreReferenceExpression || coreArg instanceof CoreTupleExpression) {
          letRef = null;
          concreteResult = concreteArg;
        } else {
          letRef = factory.local("h");
          concreteResult = factory.ref(letRef);
        }
        ConcreteExpression result = factory.app(factory.ref(ext.propExt.getRef()), true, Arrays.asList(factory.proj(concreteResult, 0), factory.proj(concreteResult, 1)));
        return typechecker.typecheck(letRef == null ? result : factory.letExpr(false, Collections.singletonList(factory.letClause(letRef, Collections.emptyList(), null, concreteArg)), result), contextData.getExpectedType());
      } else {
        TypedExpression expectedType = typechecker.typecheck(factory.app(factory.ref(ext.equationMeta.Equiv.getRef()), false, Arrays.asList(left, right)), null);
        if (expectedType == null) return null;
        TypedExpression typedArg = typechecker.typecheck(arg, expectedType.getExpression());
        if (typedArg == null) return null;
        CoreExpression actualType = typedArg.getType().normalize(NormalizationMode.WHNF);
        return typechecker.typecheck(factory.app(factory.ref(actualType instanceof CoreClassCallExpression && ((CoreClassCallExpression) actualType).getDefinition().isSubClassOf(ext.equationMeta.QEquiv) ? ext.equationMeta.qEquivToEq.getRef() : ext.equationMeta.equivToEq.getRef()), true, Collections.singletonList(factory.core(typedArg))), contextData.getExpectedType());
      }
    }

    ArendRef iRef = factory.local("i");
    return typechecker.typecheck(factory.app(factory.ref(ext.prelude.getPathCon().getRef()), true, Collections.singletonList(factory.lam(Collections.singletonList(factory.param(iRef)), factory.meta("ext_result", new MetaDefinition() {
      @Override
      public @Nullable TypedExpression invokeMeta(@NotNull ExpressionTypechecker typechecker, @NotNull ContextData contextData) {
        ConcreteExpression result = new ExtGenerator(typechecker, factory, marker, iRef).generate(arg, normType, equality.getDefCallArguments().get(1), equality.getDefCallArguments().get(2));
        return result == null ? null : typechecker.typecheck(result, normType);
      }
    })))), contextData.getExpectedType());
  }
}