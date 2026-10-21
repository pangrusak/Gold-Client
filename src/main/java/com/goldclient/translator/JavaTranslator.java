package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavaTranslator {

  public TranslationResult translate(IrMethodModel method) {
    List<IrOperationModel> operations = method.operations();
    TranslationContext context = new TranslationContext();
    StringBuilder source = new StringBuilder();
    boolean complete = true;

    source.append(returnType(method.descriptor())).append(" ").append(method.name()).append("(")
        .append(parameterList(method.descriptor())).append(") {\n");

    // Nested try statements can share one protected start label, so their
    // TRY_BEGIN markers are adjacent metadata for different region widths.
    // Order them widest-first before rendering; see orderSharedStartTryRegions.
    List<IrOperationModel> ordered = orderSharedStartTryRegions(operations);
    Map<String, Integer> labels = findLabels(ordered, 0, ordered.size());
    RenderResult rendered = renderRange(
        ordered, 0, ordered.size(), 1, context, labels);
    source.append(rendered.source());
    complete &= rendered.complete();

    // Validate the raw graph as well as the rendered structure. A renderer can
    // recover readable text from malformed IR, but that text must not be marked
    // complete when its branches or exception-table metadata are inconsistent.
    ControlFlowAudit.Report audit = ControlFlowAudit.analyze(operations);
    for (ControlFlowAudit.Issue issue : audit.errors()) {
      source.append("  // CONTROL-FLOW ERROR at operation ")
          .append(issue.operationIndex()).append(": ")
          .append(issue.message()).append("\n");
    }
    complete &= audit.valid();

    source.append("}");
    return new TranslationResult(source.toString(), complete);
  }

  private static RenderResult renderRange(
      List<IrOperationModel> operations,
      int start,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    StringBuilder source = new StringBuilder();
    boolean complete = true;

    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);

      if ("TRY_BEGIN".equals(operation.kind())) {
        // A merged split-region try renders its body flat; the individual
        // protected ranges are markers for the same handler, not nested tries.
        if (context.suppressesNestedTryRegions()) continue;
        // A loop can start at the first instruction of a protected region
        // (its header label sits after the region's TRY_BEGIN marker) while
        // its back edges are inside later protected regions or handlers.
        // Wrapping the whole range keeps those jumps as continue/break
        // instead of leaving them unresolved outside the loop.
        if (operation.operands().size() >= 2) {
          String regionStartLabel = operation.operands().get(0);
          if (!context.isActiveLoop(labelKey(regionStartLabel))
              && tryRegionStartsLoop(operations, i, end, regionStartLabel, labels)) {
            String loopLabel = labelKey(regionStartLabel);
            // The same bytecode header is rendered again as the loop itself
            // inside the protected range. A second JS label would collide;
            // a plain continue/break inside either wrapper reaches the same
            // bytecode header, so the wrapper does not need a label.
            boolean labeled = false;
            context.enterLoop(loopLabel, labeled);
            RenderResult body;
            try {
              body = renderRange(operations, i, end, indent + 1, context, labels);
            } finally {
              context.exitLoop();
            }
            source.append(indent(indent));
            if (labeled) source.append(jsLabel(loopLabel)).append(": ");
            source.append("while (true) {\n")
                .append(body.source())
                .append(indent(indent)).append("}\n");
            complete &= body.complete();
            i = end - 1;
            continue;
          }
        }
        // A region whose handler is a sibling clause of the enclosing catch
        // block repeats that handler over this block (try-with-resources and
        // catch-all plumbing); the handler is rendered once, after it.
        if (operation.operands().size() >= 3
            && context.isSuppressedTryHandler(labelKey(operation.operands().get(2)))) {
          continue;
        }
        ConditionalRender guarded = renderTryCatch(
            operations, i, end, indent, context, labels);
        if (guarded != null) {
          source.append(guarded.source());
          complete &= guarded.complete();
          i = guarded.lastIndex();
          continue;
        }
        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"try-catch-region\", \"")
            .append(escapeJava(operation.operands().toString()))
            .append("\");\n");
        complete = false;
        continue;
      }

      if ("TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind()))
        continue;

      if ("LABEL".equals(operation.kind())) {
        if (!operation.operands().isEmpty()) {
          // Prefer the loop's actual forward exit test over a nested conditional
          // that happens to branch back to this header as a continue edge.
          LoopShape forwardExitLoop = findForwardExitLoop(
              operations, i, end, operation.operands().get(0), labels);
          if (forwardExitLoop != null) {
            ConditionalRender loop = renderForwardExitLoop(
                operations, i, end, forwardExitLoop, indent, context, labels);
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
          ConditionalRender conditionJump = renderConditionJumpLoop(
              operations, i, end, indent, context, labels);
          if (conditionJump != null) {
            source.append(conditionJump.source());
            complete &= conditionJump.complete();
            i = conditionJump.lastIndex();
            continue;
          }
          int loopBranch = findBackwardConditional(
              operations, i, end, operation.operands().get(0));
          if (loopBranch > i) {
            ConditionalRender loop = renderBackwardLoop(
                operations, loopBranch, i, end, indent, context, labels,
                operations.get(loopBranch).operands().get(0));
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
          LoopShape infiniteLoop = findInfiniteLoopBackEdge(
              operations, i, end, operation.operands().get(0), labels);
          if (infiniteLoop != null) {
            ConditionalRender loop = renderInfiniteLoop(
                operations, i, end, infiniteLoop, indent, context, labels);
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
          loopBranch = findUnconditionalBackEdgeConditional(
              operations, i, end, operation.operands().get(0), labels);
          if (loopBranch > i) {
            ConditionalRender loop = renderBackwardLoop(
                operations, loopBranch, i, end, indent, context, labels,
                operations.get(loopBranch).operands().get(0));
            source.append(loop.source());
            complete &= loop.complete();
            i = loopBranch + 1;
            continue;
          }
          if (findTerminalExitLoop(operations, i, end, operation.operands().get(0), labels)) {
            ConditionalRender loop = renderTerminalExitLoop(
                operations, i, end, indent, context, labels);
            if (loop != null) {
              source.append(loop.source());
              complete &= loop.complete();
              i = loop.lastIndex();
              continue;
            }
          }
          LoopShape breakLoop = findBreakLoopBackEdge(
              operations, i, end, operation.operands().get(0), labels);
          if (breakLoop != null) {
            ConditionalRender loop = renderBreakLoop(
                operations, i, end, breakLoop, indent, context, labels);
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
          // A switch or try can place a jump back to the header before later
          // switch arms, so the back edge is not the last instruction of the
          // range. When the range ends in return/throw there is no fall-out
          // path, and the whole remaining range is this loop's body.
          if (hasOpenInfiniteLoopBackEdge(
              operations, i, end, operation.operands().get(0))) {
            ConditionalRender loop = renderOpenInfiniteLoop(
                operations, i, end, indent, context, labels);
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
        }
        continue;
      }

      if ("TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) {
        ConditionalRender switched = renderSwitch(
            operations, i, end, indent, context, labels);
        if (switched != null) {
          source.append(switched.source());
          complete &= switched.complete();
          i = switched.lastIndex();
          continue;
        }
      }

      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        String forwardSkip = context.skipExitLabel(labelKey(operation.operands().get(1)));
        if (forwardSkip != null) {
          source.append(indent(indent)).append("if (")
              .append(normalizeCondition(operation.operands().get(0)))
              .append(") break ").append(jsLabel(forwardSkip)).append(";\n");
          continue;
        }
        if (context.isBreakTarget(labelKey(operation.operands().get(1)))) {
          // An early exit of an enclosing break-loop or switch. The taken edge
          // leaves that scope, so the branch becomes a break guard.
          String breakStatement = null;
          if (context.isInnermostBreakTarget(labelKey(operation.operands().get(1)))) {
            breakStatement = "break;";
          } else {
            String switchLabel = context.switchJoinLabel(labelKey(operation.operands().get(1)));
            if (switchLabel != null) {
              breakStatement = "break " + switchLabel + ";";
            } else {
              String loopLabel = context.loopExitLabel(labelKey(operation.operands().get(1)));
              if (loopLabel != null) breakStatement = "break " + jsLabel(loopLabel) + ";";
            }
          }
          if (breakStatement != null) {
            source.append(indent(indent)).append("if (")
                .append(normalizeCondition(operation.operands().get(0)))
                .append(") ").append(breakStatement).append("\n");
            continue;
          }
        }
        ConditionalRender valueDag = renderBranchValueDag(
            operations, i, end, indent, context, labels);
        if (valueDag != null) {
          source.append(valueDag.source());
          complete &= valueDag.complete();
          i = valueDag.lastIndex();
          continue;
        }
        ConditionalRender booleanReturn = renderBooleanReturnChain(
            operations, i, end, indent, context, labels);
        if (booleanReturn != null) {
          source.append(booleanReturn.source());
          complete &= booleanReturn.complete();
          i = booleanReturn.lastIndex();
          continue;
        }
        ConditionalRender ternaryValue = renderTernaryValueMerge(
            operations, i, end, indent, context, labels);
        if (ternaryValue != null) {
          source.append(ternaryValue.source());
          complete &= ternaryValue.complete();
          i = ternaryValue.lastIndex();
          continue;
        }
        ConditionalRender constantMerge = renderConstantMergeChain(
            operations, i, end, indent, context, labels);
        if (constantMerge != null) {
          source.append(constantMerge.source());
          complete &= constantMerge.complete();
          i = constantMerge.lastIndex();
          continue;
        }
        ConditionalRender decisionTree = renderDecisionTreeValue(
            operations, i, end, indent, context, labels);
        if (decisionTree != null) {
          source.append(decisionTree.source());
          complete &= decisionTree.complete();
          i = decisionTree.lastIndex();
          continue;
        }
        ConditionalRender branchChain = renderConditionalValueChain(
            operations, i, end, indent, context, labels);
        if (branchChain != null) {
          source.append(branchChain.source());
          complete &= branchChain.complete();
          i = branchChain.lastIndex();
          continue;
        }
        ConditionalRender decisionBlocks = renderTwoBlockDecisionChain(
            operations, i, end, indent, context, labels);
        if (decisionBlocks != null) {
          source.append(decisionBlocks.source());
          complete &= decisionBlocks.complete();
          i = decisionBlocks.lastIndex();
          continue;
        }
        ConditionalRender chainIfElse = renderChainIfElse(
            operations, i, end, indent, context, labels);
        if (chainIfElse != null) {
          source.append(chainIfElse.source());
          complete &= chainIfElse.complete();
          i = chainIfElse.lastIndex();
          continue;
        }
        ConditionalRender loopEdgeGuard = renderLoopEdgeGuard(
            operations, i, end, indent, context, labels);
        if (loopEdgeGuard != null) {
          source.append(loopEdgeGuard.source());
          complete &= loopEdgeGuard.complete();
          i = loopEdgeGuard.lastIndex();
          continue;
        }
        ConditionalRender branchReturn = renderBranchToReturn(
            operations, i, end, indent, context, labels);
        if (branchReturn != null) {
          source.append(branchReturn.source());
          complete &= branchReturn.complete();
          i = branchReturn.lastIndex();
          continue;
        }
        ConditionalRender forwardDecision = renderForwardDecision(
            operations, i, end, indent, context, labels);
        if (forwardDecision != null) {
          source.append(forwardDecision.source());
          complete &= forwardDecision.complete();
          i = forwardDecision.lastIndex();
          continue;
        }
        ConditionalRender guardBlock = renderGuardBlockChain(
            operations, i, end, indent, context, labels);
        if (guardBlock != null) {
          source.append(guardBlock.source());
          complete &= guardBlock.complete();
          i = guardBlock.lastIndex();
          continue;
        }
        ConditionalRender branch = renderConditional(
            operations, i, end, indent, context, labels);

        if (branch != null) {
          source.append(branch.source());
          complete &= branch.complete();
          i = branch.lastIndex();
          continue;
        }
      }

      if ("MONITOR".equals(operation.kind())) {
        String opcode = operation.operands().isEmpty() ? "monitor" : operation.operands().get(0);
        String value = operation.operands().size() > 1 ? operation.operands().get(1) : "undefined";
        // JavaScript execution is single-threaded within this runtime. Monitor
        // enter/exit cannot preserve JVM synchronization, but making them hard
        // failures prevents otherwise useful methods from being emitted at all.
        // Keep the semantic gap visible while treating the operation as a no-op.
        source.append(indent(indent))
            .append("// Approximation: ignored JVM ").append(opcode)
            .append(" on ").append(escapeJava(value)).append(".\n");
        continue;
      }

      if ("UNSUPPORTED_DYNAMIC".equals(operation.kind())) {
        source.append(indent(indent)).append("// unsupported invokedynamic: ")
            .append(operation.operands()).append("\n");
        complete = false;
        continue;
      }

      if (("METHOD_CALL".equals(operation.kind()) || "CALL_VALUE".equals(operation.kind()))
          && i + 1 < end
          && "THROW".equals(operations.get(i + 1).kind())
          && !operation.operands().isEmpty()
          && operation.operands().get(0).trim().startsWith("new ")) {
        source.append(indent(indent))
            .append("throw ")
            .append(normalizeExpression(operation.operands().get(0)))
            .append(";\n");
        i++;
        continue;
      }

      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        Integer targetIndex = labels.get(labelKey(target));

        String forwardSkip = context.skipExitLabel(labelKey(target));
        if (forwardSkip != null) {
          source.append(indent(indent)).append("break ")
              .append(jsLabel(forwardSkip)).append(";\n");
          continue;
        }

        if (context.isActiveLoop(labelKey(target))) {
          source.append(indent(indent)).append("continue");
          if (context.hasLoopLabel(labelKey(target))) {
            source.append(" ").append(jsLabel(target));
          }
          source.append(";\n");
          continue;
        }

        if (context.isBreakTarget(labelKey(target))) {
          if (context.isInnermostBreakTarget(labelKey(target))) {
            source.append(indent(indent)).append("break;\n");
          } else {
            // A nested loop or switch sits between this jump and the scope it
            // leaves, so a plain break would leave the wrong scope. A labeled
            // break still reaches the intended switch join or loop exit.
            String switchLabel = context.switchJoinLabel(labelKey(target));
            String loopLabel = switchLabel != null
                ? null : context.loopExitLabel(labelKey(target));
            if (switchLabel != null) {
              source.append(indent(indent)).append("break ")
                  .append(switchLabel).append(";\n");
            } else if (loopLabel != null) {
              source.append(indent(indent)).append("break ")
                  .append(jsLabel(loopLabel)).append(";\n");
            } else {
              source.append(indent(indent))
                  .append("GoldClientRuntime.unsupported(\"JUMP\", \"")
                  .append(escapeJava(String.join(" | ", operation.operands())))
                  .append("\");\n");
              complete = false;
            }
          }
          continue;
        }

        // Several jumps often share the same loop edge through a block whose
        // only content is another jump (javac places it for conditional
        // expressions in loop tests). Following that chain keeps the shared
        // edge visible as continue/break instead of leaving it unresolved.
        String edgeAction = loopEdgeAction(operations, target, context, labels);
        if (edgeAction != null) {
          source.append(indent(indent)).append(edgeAction).append(";\n");
          continue;
        }

        // Early exits of a for loop are routed through the increment block: a
        // jump to that tail has to run its statements before continuing.
        // Duplicating the tail for this jump keeps the update on every path.
        int loopTail = targetIndex == null
            ? -1
            : loopTailJumpIndex(operations, targetIndex, context);
        if (loopTail >= 0) {
          RenderResult tail = renderRange(
              operations, targetIndex, loopTail + 1, indent, context, labels);
          source.append(tail.source());
          complete &= tail.complete();
          continue;
        }

        if (targetIndex != null
            && (targetIndex == end || isFallthroughJump(operations, i, targetIndex))) {
          // A goto whose destination is the next executable instruction does
          // not change control flow. Bytecode often keeps this jump after
          // compiler-generated labels, so don't report it as unsupported.
          continue;
        }
        if (targetIndex != null && targetIndex > i && targetIndex < end
            && isIsolatedForwardJump(operations, i, targetIndex)) {
          // A forward goto can skip a dead bytecode block. Drop that block only
          // when no instruction outside it branches to any label inside it.
          // This handles dead compiler-generated blocks without erasing an
          // alternate branch or guessing at the meaning of a live jump.
          i = targetIndex - 1;
          continue;
        }
        if (targetIndex != null && targetIndex > i && targetIndex != end) {
          // A shared loop-continuation tail sits after the loop body: it
          // evaluates the loop guard and then continues or leaves the loop.
          // A jump into that tail has to execute the guard, so inline the tail
          // at this edge instead of treating the label as an unsupported jump.
          int tailLimit = forwardLoopTailLimit(operations, targetIndex, context, labels);
          if (tailLimit > targetIndex) {
            int[] extent = {targetIndex};
            int[] budget = {64};
            RenderResult tail = renderForwardArm(
                operations, targetIndex, tailLimit, indent, context, labels,
                extent, budget, 0);
            if (tail != null && tail.complete() && extent[0] < tailLimit) {
              source.append(tail.source());
              continue;
            }
          }
        }
        if (targetIndex != null
            && jumpChainEndsInVoidReturn(operations, targetIndex, labels)) {
          // Several arms can share one exit block whose only content is a jump
          // to the method's final return, or the jump may target a label placed
          // immediately before the return. Following that chain keeps the exit
          // visible as a return instead of running code laid out between the
          // shared labels.
          source.append(indent(indent)).append("return;\n");
          continue;
        }
        if (targetIndex != null && targetIndex > i) {
          // A jump can leave a nested arm for a shared tail that computes and
          // returns a value. The tail is straight-line, so rendering it here
          // duplicates it instead of falling into unrelated code laid out
          // between the arm and its label.
          int inlineReturnEnd = straightLineReturnEnd(operations, targetIndex);
          if (inlineReturnEnd >= 0) {
            RenderResult tail = renderRange(
                operations, targetIndex, inlineReturnEnd + 1, indent, context, labels);
            source.append(tail.source());
            complete &= tail.complete();
            continue;
          }
        }

        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"JUMP\", \"")
            .append(escapeJava(operation.operands().toString()))
            .append("\");\n");
        complete = false;
        continue;
      }

      String line = translateOperation(operation, context);
      if (line == null) {
        if (isExpressionOnly(operation))
          continue;

        // Keep emitting syntactically valid JavaScript even when this opcode
        // is not implemented yet. The runtime failure is explicit and retains
        // the operation details for debugging; completeness stays false.
        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"")
            .append(escapeJava(operation.kind()))
            .append("\", \"")
            .append(escapeJava(String.join(" | ", operation.operands())))
            .append("\");\n");
        complete = false;
      } else if (!line.isBlank()) {
        source.append(indent(indent)).append(line).append("\n");
      }
    }

    return new RenderResult(source.toString(), complete);
  }

  /**
   * Orders exception-table markers that share one protected start label so the
   * widest region comes first. A nested {@code try} can begin at exactly the
   * same bytecode label as its enclosing {@code try}; both markers are emitted
   * before that shared label, in no guaranteed order. Rendering the inner
   * region first would consume the marker position and leave the outer
   * protected range and handler looking like ordinary statements.
   */
  private static List<IrOperationModel> orderSharedStartTryRegions(
      List<IrOperationModel> operations) {
    List<IrOperationModel> ordered = new ArrayList<>(operations);
    Map<String, Integer> labels = findLabels(ordered, 0, ordered.size());
    int i = 0;
    while (i < ordered.size()) {
      IrOperationModel first = ordered.get(i);
      if (!"TRY_BEGIN".equals(first.kind()) || first.operands().size() < 2) {
        i++;
        continue;
      }
      String start = labelKey(first.operands().get(0));
      int groupEnd = i + 1;
      while (groupEnd < ordered.size()) {
        IrOperationModel candidate = ordered.get(groupEnd);
        if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 2
            || !start.equals(labelKey(candidate.operands().get(0)))) break;
        groupEnd++;
      }
      if (groupEnd - i > 1) {
        List<IrOperationModel> group = new ArrayList<>(ordered.subList(i, groupEnd));
        // Same start label, so the larger protected end is the outer region.
        // A stable descending sort keeps equal-width multi-catch entries together.
        group.sort(Comparator.comparingInt(
            operation -> -protectedEndIndex(operation, labels)));
        for (int slot = 0; slot < group.size(); slot++) ordered.set(i + slot, group.get(slot));
      }
      i = groupEnd;
    }
    return ordered;
  }

  /**
   * True when the protected body jumps to its own end label before the normal
   * completion path. A jump immediately followed by the handler marker is the
   * normal path the try renderer already handles; only an earlier jump needs a
   * labeled block so it can leave the try body.
   */
  private static boolean bodyJumpsPastProtectedEnd(
      List<IrOperationModel> operations,
      int start,
      int end,
      String label,
      Map<String, Integer> labels) {
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      if (!labelKey(label).equals(labelKey(operation.operands().get(0)))) continue;
      int cursor = i + 1;
      while (cursor < end) {
        IrOperationModel next = operations.get(cursor);
        if ("LABEL".equals(next.kind()) || "TRY_BEGIN".equals(next.kind())
            || "TRY_END".equals(next.kind())) {
          cursor++;
          continue;
        }
        break;
      }
      if (cursor >= end || "CATCH_BEGIN".equals(operations.get(cursor).kind())) continue;
      return true;
    }
    return false;
  }

  private static int protectedEndIndex(
      IrOperationModel operation, Map<String, Integer> labels) {
    if (operation.operands().size() < 2) return -1;
    Integer index = labels.get(labelKey(operation.operands().get(1)));
    return index == null ? -1 : index;
  }

  /**
   * Structures a conventional JVM exception table entry as a source try/catch.
   * Complex/malformed regions are left explicitly incomplete rather than
   * silently claiming that exception behavior was preserved.
   */
  private static ConditionalRender renderGroupedTryCatch(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    ConditionalRender declaredFinally = renderDeclaredFinally(
        operations, begin, end, indent, context, labels);
    if (declaredFinally != null) return declaredFinally;
    List<String> metadata = operations.get(begin).operands();
    if (metadata.size() < 4) return null;
    String startLabel = metadata.get(0);
    String endLabel = metadata.get(1);
    String handlerLabel = metadata.get(2);
    String exceptionType = metadata.get(3);
    // Group this protected range's exception-table entries by handler. Entries
    // that share one handler are a JVM multi-catch; distinct handlers become
    // distinct catch clauses.
    List<String> handlerLabels = new java.util.ArrayList<>();
    Map<String, Set<String>> handlerTypes = new java.util.LinkedHashMap<>();
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 4) continue;
      List<String> candidateMetadata = candidate.operands();
      if (!labelKey(startLabel).equals(labelKey(candidateMetadata.get(0)))
          || !labelKey(endLabel).equals(labelKey(candidateMetadata.get(1)))) continue;
      String candidateHandler = labelKey(candidateMetadata.get(2));
      if (!handlerTypes.containsKey(candidateHandler)) {
        handlerLabels.add(candidateHandler);
        handlerTypes.put(candidateHandler, new java.util.LinkedHashSet<>());
      }
      handlerTypes.get(candidateHandler).add(candidateMetadata.get(3));
    }
    if (handlerLabels.isEmpty()) {
      handlerLabels.add(labelKey(handlerLabel));
      handlerTypes.put(labelKey(handlerLabel), new java.util.LinkedHashSet<>());
      handlerTypes.get(labelKey(handlerLabel)).add(exceptionType);
    }

    int tryEnd = -1;
    int[] handlerBegins = new int[handlerLabels.size()];
    java.util.Arrays.fill(handlerBegins, -1);
    for (int i = begin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (operation.operands().size() < 3) continue;
      if (!labelKey(startLabel).equals(labelKey(operation.operands().get(0)))
          || !labelKey(endLabel).equals(labelKey(operation.operands().get(1)))) continue;
      int slot = handlerLabels.indexOf(labelKey(operation.operands().get(2)));
      if (slot < 0) continue;
      if ("TRY_END".equals(operation.kind())) tryEnd = i;
      if ("CATCH_BEGIN".equals(operation.kind()) && handlerBegins[slot] < 0) {
        handlerBegins[slot] = i;
      }
    }
    if (tryEnd <= begin || handlerBegins[0] <= tryEnd) {
      // Some javac/ASM exception tables carry normalized labels that no
      // longer match the marker operands after nested-region reconstruction.
      // If the structural markers are still ordered, preserve the exception
      // boundary instead of discarding the whole region.
      tryEnd = -1;
      java.util.Arrays.fill(handlerBegins, -1);
      int slot = 0;
      for (int i = begin + 1; i < end && slot < handlerBegins.length; i++) {
        if (tryEnd < 0 && "TRY_END".equals(operations.get(i).kind())) tryEnd = i;
        if (tryEnd >= 0 && "CATCH_BEGIN".equals(operations.get(i).kind())) {
          handlerBegins[slot++] = i;
        }
      }
      if (tryEnd <= begin || handlerBegins[0] <= tryEnd) return null;
    }
    for (int slot = 1; slot < handlerBegins.length; slot++) {
      if (handlerBegins[slot] < 0) return null;
    }
    // Keep handlers in source order even when the exception table lists them
    // in another order.
    for (int a = 0; a < handlerBegins.length; a++) {
      for (int b = a + 1; b < handlerBegins.length; b++) {
        if (handlerBegins[b] < handlerBegins[a]) {
          int marker = handlerBegins[a];
          handlerBegins[a] = handlerBegins[b];
          handlerBegins[b] = marker;
          String label = handlerLabels.get(a);
          handlerLabels.set(a, handlerLabels.get(b));
          handlerLabels.set(b, label);
        }
      }
    }
    int lastCatchBegin = handlerBegins[handlerBegins.length - 1];

    // A string switch compiles to one protected range per case, so the switch
    // targets lie past this first range's TRY_END even though they share the
    // same handler. Let the split-region renderer merge them instead of
    // producing a clipped try body with unresolved switch targets.
    if (isSplitSwitchRegion(
        operations, begin, end, tryEnd, handlerLabel, exceptionType, labels)) {
      return null;
    }

    String normalJoinLabel = null;
    for (int i = tryEnd + 1; i < handlerBegins[0]; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      String candidateJoin = operation.operands().get(0);
      Integer candidateJoinIndex = labels.get(labelKey(candidateJoin));
      if (candidateJoinIndex != null && candidateJoinIndex > lastCatchBegin) {
        normalJoinLabel = candidateJoin;
      }
    }

    int handlerEnd = end;
    String joinLabel = null;
    // A handler can end where the normal continuation block begins, and that
    // block may itself be protected. Its TRY_BEGIN marker is emitted before the
    // join label, so stop at the marker and let the following region render it.
    boolean handlerEndsAtJoinRegion = false;
    for (int i = lastCatchBegin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);

      if (normalJoinLabel != null && "TRY_BEGIN".equals(operation.kind())
          && !operation.operands().isEmpty()
          && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
        handlerEnd = i;
        handlerEndsAtJoinRegion = true;
        break;
      }

      // Nested close()/cleanup regions may have their own joins; structure them
      // first and don't mistake their internal branches for the outer handler exit.
      if ("TRY_BEGIN".equals(operation.kind())) {
        ConditionalRender nested = renderTryCatch(
            operations, i, end, indent, context, labels);
        if (nested != null) {
          i = nested.lastIndex();
          continue;
        }
      }

      if (normalJoinLabel != null) {
        if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty()
            && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
          handlerEnd = i;
          joinLabel = normalJoinLabel;
          break;
        }
        if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
            && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
          // A switch arm or nested block can jump to the shared join before the
          // handler's final instruction. That is an inner edge, not the end of
          // the handler: keep scanning for the actual join label so the whole
          // handler (including the switch) stays in the catch clause.
          Integer joinTarget = labels.get(labelKey(normalJoinLabel));
          if (joinTarget != null && joinTarget > i && joinTarget < end
              && joinTarget < operations.size()
              && "LABEL".equals(operations.get(joinTarget).kind())) {
            continue;
          }
          handlerEnd = i;
          joinLabel = normalJoinLabel;
          break;
        }
      } else if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        Integer targetIndex = labels.get(labelKey(target));
        Integer protectedEnd = labels.get(labelKey(endLabel));
        // Backward jumps are loop/continue edges inside the handler, not joins.
        if (targetIndex != null && targetIndex > i
            && protectedEnd != null && targetIndex > protectedEnd) {
          handlerEnd = i;
          joinLabel = target;
          break;
        }
      }
      if (isTerminal(operation)
          && !hasForwardBranchPast(operations, lastCatchBegin + 1, i, end, labels)) {
        // A return/throw inside a loop or switch arm is not the handler tail
        // when an earlier branch still jumps to code after it. In that case the
        // handler continues and the real end is its join label.
        handlerEnd = i + 1;
        break;
      }
    }
    if (handlerEnd <= lastCatchBegin + 1) return null;

    // Each handler before the last one ends where the next handler's marker
    // begins. A trailing jump that reaches the point where a JavaScript catch
    // clause resumes anyway is dropped; a jump that leaves the region for a
    // block past the handlers keeps its own path by rendering that block inside
    // the clause or leaving it through a labeled break.
    String continuationLabel = joinLabel != null ? joinLabel : normalJoinLabel;
    int resumeIndex = joinLabel == null ? handlerEnd : handlerEnd + 1;

    // The normal path may jump to a block that is laid out before the
    // continuation its catch clauses fall out to (javac places catch-only code
    // first and shares the tail). A plain fall-out of the try block would run
    // that catch-only code, so the path either continues inside the try body or
    // leaves it through a labeled break.
    int normalCompletionEnd = handlerBegins[0];
    int normalCompletionJump = -1;
    if (normalJoinLabel != null) {
      for (int i = tryEnd + 1; i < handlerBegins[0]; i++) {
        IrOperationModel operation = operations.get(i);
        if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
            && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
          normalCompletionEnd = i;
          normalCompletionJump = i;
        }
      }
    }
    Integer normalTarget =
        normalJoinLabel == null ? null : labels.get(labelKey(normalJoinLabel));
    // A target just past the range boundary belongs to the enclosing block
    // (javac may place exception markers between the range end and the label).
    // It cannot be inlined here and must be reached with a labeled break.
    boolean targetAtRangeExit = normalTarget != null && normalTarget >= end
        && allExceptionMarkers(operations, end, normalTarget);
    boolean escapingNormalPath = normalCompletionJump >= 0 && normalTarget != null
        && (normalTarget < end || targetAtRangeExit)
        && normalTarget > resumeIndex
        && !fallsThroughTo(normalTarget, resumeIndex, operations);
    boolean inlineTailBlock = escapingNormalPath && normalTarget < end
        && canInlineJumpBlock(operations, end);
    // A jump from inside the protected body to its end label also leaves the
    // try normally. Wrap the region in a block so that edge can break out
    // instead of being lowered as an unsupported jump to a label the body
    // renderer cannot reach.
    boolean bodyExitsAtEnd = bodyJumpsPastProtectedEnd(
        operations, begin + 1, tryEnd, endLabel, labels);
    String breakLabel = (escapingNormalPath && !inlineTailBlock) || bodyExitsAtEnd
        ? "_try" + begin : null;

    int[] handlerEnds = new int[handlerBegins.length];
    int[] handlerTailBlocks = new int[handlerBegins.length];
    boolean[] handlerBreaksToNormal = new boolean[handlerBegins.length];
    java.util.Arrays.fill(handlerTailBlocks, -1);
    handlerEnds[handlerBegins.length - 1] = handlerEnd;
    for (int slot = 0; slot + 1 < handlerBegins.length; slot++) {
      int limit = handlerBegins[slot + 1];
      int tailIndex = limit - 1;
      while (tailIndex > handlerBegins[slot]
          && isLoopExitMetadata(operations.get(tailIndex))) tailIndex--;
      if (tailIndex > handlerBegins[slot]) {
        IrOperationModel tail = operations.get(tailIndex);
        if ("JUMP".equals(tail.kind()) && !tail.operands().isEmpty()) {
          Integer tailTarget = labels.get(labelKey(tail.operands().get(0)));
          boolean sameContinuation = continuationLabel != null
              && labelKey(continuationLabel).equals(labelKey(tail.operands().get(0)));
          if (sameContinuation
              || (tailTarget != null && fallsThroughTo(tailTarget, resumeIndex, operations))) {
            limit = tailIndex;
          } else if (breakLabel != null && tailTarget != null
              && tailTarget.equals(normalTarget)) {
            handlerBreaksToNormal[slot] = true;
            limit = tailIndex;
          } else if (tailTarget != null && canInlineJumpBlock(operations, end)) {
            handlerTailBlocks[slot] = tailTarget;
            limit = tailIndex;
          }
        }
      }
      handlerEnds[slot] = limit;
    }

    // Multiple exception-table entries can share a protected range and handler
    // (Java multi-catch). Start after the actual start label so adjacent
    // TRY_BEGIN metadata records are never mistaken for executable nested regions.
    Integer startIndex = labels.get(labelKey(startLabel));
    int bodyStart = startIndex == null ? begin + 1 : startIndex + 1;
    // A nested try statement can share this region's protected start label;
    // its marker is adjacent to this region's markers, before the shared
    // label. Start at that marker so the inner region is rendered inside this
    // try block instead of being skipped as surrounding metadata.
    int sharedStartNested = -1;
    int sharedStartLimit = startIndex == null ? tryEnd : startIndex;
    for (int i = begin + 1; i < sharedStartLimit; i++) {
      IrOperationModel candidate = operations.get(i);
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 2) continue;
      if (!labelKey(startLabel).equals(labelKey(candidate.operands().get(0)))) continue;
      if (labelKey(endLabel).equals(labelKey(candidate.operands().get(1)))) continue;
      sharedStartNested = i;
      break;
    }
    if (sharedStartNested >= 0) {
      bodyStart = sharedStartNested;
    } else {
      while (bodyStart < tryEnd
          && ("LABEL".equals(operations.get(bodyStart).kind())
              || "TRY_BEGIN".equals(operations.get(bodyStart).kind()))) bodyStart++;
      // A protected range can start exactly at a loop header (try inside the
      // loop body, or a try-with-resources body that is itself a loop). Keep the
      // header label so the body renderer can structure the back edge instead of
      // leaving it as an unresolved jump.
      if (startIndex != null && isBackwardBranchTarget(
          operations, startIndex, tryEnd, startLabel, labels)) {
        bodyStart = startIndex;
      }
    }
    // A region that starts exactly at this region's end label emits its
    // TRY_BEGIN marker before this region's TRY_END. The body has to stop at
    // that marker: the nested region belongs to the normal completion path,
    // which is rendered inside the try block below.
    int bodyLimit = tryEnd;
    Integer protectedEndIndex = labels.get(labelKey(endLabel));
    if (protectedEndIndex != null) {
      for (int i = bodyStart; i < tryEnd; i++) {
        IrOperationModel candidate = operations.get(i);
        if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().isEmpty()) continue;
        Integer nestedStart = labels.get(labelKey(candidate.operands().get(0)));
        if (nestedStart != null && nestedStart >= protectedEndIndex) {
          bodyLimit = i;
          break;
        }
      }
    }
    if (bodyLimit < bodyStart) bodyLimit = bodyStart;
    // Nested regions may place their handlers after this region's TRY_END:
    // javac emits try-with-resources cleanup after the protected range. The
    // body has to reach those handlers, but never past this region's own
    // handler clauses.
    boolean extendedBody = false;
    if (bodyLimit == tryEnd && handlerBegins[0] > bodyLimit) {
      int handlerLimit = handlerBegins[0];
      for (int i = bodyStart; i < bodyLimit; i++) {
        IrOperationModel candidate = operations.get(i);
        if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 3) continue;
        String nestedStart = candidate.operands().get(0);
        String nestedEnd = candidate.operands().get(1);
        boolean extendsBeyond = false;
        for (int j = bodyLimit; j < handlerLimit; j++) {
          IrOperationModel marker = operations.get(j);
          if (marker.operands().size() >= 3
              && labelKey(nestedStart).equals(labelKey(marker.operands().get(0)))
              && labelKey(nestedEnd).equals(labelKey(marker.operands().get(1)))) {
            extendsBeyond = true;
            break;
          }
        }
        if (extendsBeyond) {
          bodyLimit = handlerLimit;
          extendedBody = true;
          break;
        }
      }
    }
    // The normal path may leave the try body for the block after the handler
    // clauses. Falling out of the generated try/catch reaches that block, so
    // stop before the completion jump instead of lowering it.
    if (normalCompletionJump >= 0 && normalCompletionJump < bodyLimit
        && normalTarget != null && handlerEnd >= lastCatchBegin
        && normalTarget >= handlerEnd) {
      bodyLimit = normalCompletionJump;
    }
    if (bodyExitsAtEnd) context.enterSkipExit(endLabel, breakLabel);
    RenderResult tryBodyCore;
    try {
      tryBodyCore = renderRange(
          operations, bodyStart, bodyLimit, indent + 1, context, labels);
    } finally {
      if (bodyExitsAtEnd) context.exitSkipExit();
    }
    StringBuilder tryBodySource = new StringBuilder(tryBodyCore.source());
    boolean tryBodyComplete = tryBodyCore.complete();
    boolean renderedNormalPath = extendedBody;
    if (bodyLimit < tryEnd) {
      RenderResult normalPath = renderRange(
          operations, bodyLimit, handlerBegins[0], indent + 1, context, labels);
      tryBodySource.append(normalPath.source());
      tryBodyComplete &= normalPath.complete();
      renderedNormalPath = true;
    }
    // The normal path often contains its return/cleanup immediately after
    // TRY_END. Keep that path inside the generated try block instead of
    // skipping it when the catch handlers are consumed.
    if (!renderedNormalPath && normalCompletionEnd > tryEnd + 1
        && isSimpleNormalCompletion(operations, tryEnd + 1, normalCompletionEnd)) {
      RenderResult normalCompletion = renderRange(
          operations, tryEnd + 1, normalCompletionEnd, indent + 1, context, labels);
      tryBodySource.append(normalCompletion.source());
      tryBodyComplete &= normalCompletion.complete();
    }
    if (escapingNormalPath) {
      if (inlineTailBlock) {
        RenderResult tailBlock = renderRange(
            operations, inlinedBlockStart(operations, normalTarget, labels), end,
            indent + 1, context, labels);
        tryBodySource.append(tailBlock.source());
        tryBodyComplete &= tailBlock.complete();
      } else {
        tryBodySource.append(indent(indent + 1)).append("break ").append(breakLabel)
            .append(";\n");
      }
    }

    StringBuilder source = new StringBuilder();
    if (breakLabel != null) {
      source.append(indent(indent)).append(breakLabel).append(": {\n");
    }
    source.append(indent(indent)).append("try {\n").append(tryBodySource);
    boolean complete = tryBodyComplete;
    for (int slot = 0; slot < handlerBegins.length; slot++) {
      int handlerStart = handlerBegins[slot] + 1;
      int slotEnd = handlerEnds[slot];
      String exceptionVariable = "caughtException";
      // A handler label is normally the first instruction after CATCH_BEGIN.
      // Skip labels, remaining marker entries, and protective region records
      // before looking for the JVM's exception-stack-to-local store.
      while (handlerStart < slotEnd
          && ("LABEL".equals(operations.get(handlerStart).kind())
              || "CATCH_BEGIN".equals(operations.get(handlerStart).kind())
              || "TRY_BEGIN".equals(operations.get(handlerStart).kind()))) {
        handlerStart++;
      }
      if (handlerStart < slotEnd) {
        IrOperationModel first = operations.get(handlerStart);
        if ("LOCAL_WRITE".equals(first.kind()) && first.operands().size() >= 2
            && "<exception>".equals(first.operands().get(1))) {
          exceptionVariable = first.operands().get(0);
          handlerStart++;
        }
      }
      context.enterSuppressedTryHandlers(handlerTypes.keySet());
      StringBuilder clauseBody = new StringBuilder();
      try {
        RenderResult catchBody = renderRange(
            operations, handlerStart, slotEnd, indent + 1, context, labels);
        complete &= catchBody.complete();
        clauseBody.append(catchBody.source());
        if (handlerTailBlocks[slot] >= 0) {
          RenderResult tailBlock = renderRange(
              operations, inlinedBlockStart(operations, handlerTailBlocks[slot], labels),
              end, indent + 1, context, labels);
          complete &= tailBlock.complete();
          clauseBody.append(tailBlock.source());
        }
        if (handlerBreaksToNormal[slot]) {
          clauseBody.append(indent(indent + 1)).append("break ").append(breakLabel)
              .append(";\n");
        }
      } finally {
        context.exitSuppressedTryHandlers();
      }
      Set<String> types = handlerTypes.get(handlerLabels.get(slot));
      if (types == null || types.isEmpty()) types = java.util.Set.of(exceptionType);
      source.append(indent(indent)).append("} catch (");
      if (types.size() > 1) {
        // JVM multi-catch shares one handler for several exception-table entries.
        // JavaScript has one catch clause, so dispatch by runtime type and rethrow
        // exceptions that do not match any listed alternative.
        source.append("caughtException) {\n");
        source.append(indent(indent + 1)).append("if (!(");
        int typeIndex = 0;
        for (String type : types) {
          if (typeIndex++ > 0) source.append(" || ");
          source.append("GoldClientRuntime.instanceOf(caughtException, \"")
              .append(escapeJava(type)).append("\")");
        }
        source.append(")) throw caughtException;\n");
        if (!"caughtException".equals(exceptionVariable)) {
          source.append(indent(indent + 1)).append("var ").append(exceptionVariable)
              .append(" = caughtException;\n");
        }
        source.append(clauseBody);
      } else {
        source.append(types.iterator().next()).append(" ").append(exceptionVariable)
            .append(") {\n")
            .append(clauseBody);
      }
    }
    source.append(indent(indent)).append("}\n");
    // Code between the point the catch clauses fall out to and the block the
    // normal path jumps to only runs when an exception was handled.
    int normalBlockStart = normalTarget == null
        ? -1 : inlinedBlockStart(operations, normalTarget, labels);
    if (breakLabel != null) {
      // Stop before a sibling handler of an enclosing region: that block is
      // rendered by the enclosing try and does not run after this catch.
      int catchOnlyEnd = normalBlockStart;
      for (int i = resumeIndex; i < catchOnlyEnd; i++) {
        if ("CATCH_BEGIN".equals(operations.get(i).kind())) {
          catchOnlyEnd = i;
          break;
        }
      }
      RenderResult catchOnly = renderRange(
          operations, resumeIndex, catchOnlyEnd, indent + 1, context, labels);
      complete &= catchOnly.complete();
      source.append(catchOnly.source());
      source.append(indent(indent)).append("}\n");
    }
    // A handler may legitimately terminate with return/throw instead of
    // jumping to a shared continuation. That is structured control flow, not
    // evidence that the exception region was only partially rendered.
    int lastHandlerEnd = handlerEnds[handlerEnds.length - 1];
    if (!handlerEndsAtJoinRegion && joinLabel == null && lastHandlerEnd < end
        && !isTerminal(operations.get(lastHandlerEnd - 1))) complete = false;
    int last = breakLabel != null && normalBlockStart >= 0
        ? normalBlockStart - 1
        : (joinLabel == null || handlerEndsAtJoinRegion
            ? lastHandlerEnd - 1 : lastHandlerEnd);
    return new ConditionalRender(source.toString(), complete, last);
  }

  /**
   * Renders the JVM's catch-all/finally shape produced by try-with-resources
   * and try/finally. In that shape a protected range has one ordinary handler
   * that stores and rethrows the primary exception, plus a null-type handler
   * that performs cleanup and rethrows. The null-type entry may also protect
   * the ordinary handler's code.
   *
   * <p>Rendering the cleanup as a sibling catch clause is wrong: the ordinary
   * handler already catches the same Throwable, so the cleanup clause is
   * unreachable and the normal path loses its close/cleanup. This method only
   * accepts the simple, unambiguous shape; anything else falls back to the
   * general renderer and is reported as incomplete rather than guessed.</p>
   */
  private static ConditionalRender renderDeclaredFinally(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    List<String> metadata = operations.get(begin).operands();
    if (metadata.size() < 5) return null;
    String startLabel = metadata.get(0);
    String endLabel = metadata.get(1);
    String finallyLabel = labelKey(metadata.get(2));

    List<String> typedHandlers = new java.util.ArrayList<>();
    Set<String> typedTypes = new java.util.LinkedHashSet<>();
    boolean seenFinally = false;
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 5) continue;
      List<String> candidateMetadata = candidate.operands();
      if (!labelKey(startLabel).equals(labelKey(candidateMetadata.get(0)))
          || !labelKey(endLabel).equals(labelKey(candidateMetadata.get(1)))) continue;
      String handler = labelKey(candidateMetadata.get(2));
      if ("true".equals(candidateMetadata.get(4))) {
        if (seenFinally && !finallyLabel.equals(handler)) return null;
        finallyLabel = handler;
        seenFinally = true;
      } else if (!typedHandlers.contains(handler)) {
        typedHandlers.add(handler);
        typedTypes.add(candidateMetadata.get(3));
      } else {
        // Multi-catch shares one handler. Leave it to the general renderer.
        return null;
      }
    }
    if (!seenFinally || typedHandlers.size() != 1 || typedTypes.size() != 1) return null;

    int tryEnd = -1;
    int typedBegin = -1;
    int finallyBegin = -1;
    for (int i = begin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (operation.operands().size() < 4) continue;
      if (!labelKey(startLabel).equals(labelKey(operation.operands().get(0)))
          || !labelKey(endLabel).equals(labelKey(operation.operands().get(1)))) continue;
      if ("TRY_END".equals(operation.kind()) && tryEnd < 0) tryEnd = i;
      if (!"CATCH_BEGIN".equals(operation.kind())) continue;
      String handler = labelKey(operation.operands().get(2));
      if (handler.equals(finallyLabel)) {
        if (finallyBegin < 0) finallyBegin = i;
      } else if (handler.equals(typedHandlers.get(0)) && typedBegin < 0) {
        typedBegin = i;
      }
    }
    if (tryEnd < 0 || typedBegin < 0 || finallyBegin < 0 || typedBegin > finallyBegin) {
      return null;
    }

    // The ordinary handler must be exactly a store-and-rethrow path. A handler
    // that also inlines cleanup would be cleaned twice by the finally.
    int typedStart = typedBegin + 1;
    while (typedStart < finallyBegin && isExceptionBoundary(operations.get(typedStart))) {
      typedStart++;
    }
    if (typedStart >= finallyBegin) return null;
    IrOperationModel typedStore = operations.get(typedStart);
    if (!"LOCAL_WRITE".equals(typedStore.kind()) || typedStore.operands().size() < 2
        || !"<exception>".equals(typedStore.operands().get(1))) return null;
    String typedVariable = typedStore.operands().get(0);
    if (!isSimpleRethrowHandler(operations, typedStart, finallyBegin, typedVariable)) {
      return renderUserCatchFinally(
          operations, end, indent, context, labels, startLabel, endLabel,
          typedHandlers.get(0), typedTypes.iterator().next(), typedVariable,
          finallyLabel, labels.get(labelKey(startLabel)), tryEnd,
          typedStart, finallyBegin);
    }
    int typedThrow = -1;
    for (int i = typedStart + 1; i < finallyBegin; i++) {
      IrOperationModel operation = operations.get(i);
      if ("THROW".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(typedVariable).equals(labelKey(operation.operands().get(0)))) {
        typedThrow = i;
        break;
      }
      if (!isSimpleRethrowOperation(operation)) return null;
    }
    if (typedThrow < 0) return null;
    // The synthetic try-with-resources handler captures the primary exception
    // in another local before rethrowing; the cleanup reads that local later.
    // Requiring the capture avoids treating a user-written catch-and-rethrow
    // (whose inlined cleanup could already be in the body) as this shape.
    boolean capturesPrimary = false;
    for (int i = typedStart + 1; i < typedThrow; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LOCAL_WRITE".equals(operation.kind()) && operation.operands().size() >= 2
          && labelKey(typedVariable).equals(labelKey(operation.operands().get(1)))) {
        capturesPrimary = true;
      }
    }
    if (!capturesPrimary) return null;

    // The catch-all handler must store its exception, run cleanup, and rethrow
    // that same value. The rethrow is the handler's exit, not user code.
    int finallyStart = finallyBegin + 1;
    while (finallyStart < end && isExceptionBoundary(operations.get(finallyStart))) {
      finallyStart++;
    }
    if (finallyStart >= end) return null;
    IrOperationModel finallyStore = operations.get(finallyStart);
    if (!"LOCAL_WRITE".equals(finallyStore.kind()) || finallyStore.operands().size() < 2
        || !"<exception>".equals(finallyStore.operands().get(1))) return null;
    String finallyVariable = finallyStore.operands().get(0);
    Integer thisStart = labels.get(labelKey(startLabel));
    if (thisStart == null) return null;
    int finallyThrow = -1;
    for (int i = finallyStart + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CATCH_BEGIN".equals(operation.kind()) && operation.operands().size() >= 3) {
        Integer nestedStart = labels.get(labelKey(operation.operands().get(0)));
        if (nestedStart == null || nestedStart < thisStart) break;
      }
      if ("THROW".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(finallyVariable).equals(labelKey(operation.operands().get(0)))) {
        finallyThrow = i;
      }
    }
    if (finallyThrow < 0) return null;

    // The catch-all must also protect the ordinary handler's body; that is
    // what makes it a finally rather than an unrelated sibling catch.
    boolean protectsTypedHandler = false;
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 5) continue;
      List<String> candidateMetadata = candidate.operands();
      if (labelKey(typedHandlers.get(0)).equals(labelKey(candidateMetadata.get(0)))
          && finallyLabel.equals(labelKey(candidateMetadata.get(2)))
          && "true".equals(candidateMetadata.get(4))) {
        protectsTypedHandler = true;
        break;
      }
    }
    if (!protectsTypedHandler) return null;

    // A normal-path jump into a loop header (or back inside the region) cannot
    // be represented by falling out of a finally. Leave that shape incomplete.
    for (int i = tryEnd + 1; i < typedBegin; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      Integer target = labels.get(labelKey(operation.operands().get(0)));
      if (target == null || target < finallyThrow) return null;
    }

    int bodyStart = thisStart + 1;
    while (bodyStart < tryEnd && isExceptionBoundary(operations.get(bodyStart))) bodyStart++;
    if (bodyStart >= tryEnd) return null;
    // The try-with-resources body may itself be a loop that starts at this
    // region's start label. Keep that label so the loop back edge is structured.
    if (isBackwardBranchTarget(operations, thisStart, tryEnd, startLabel, labels)) {
      bodyStart = thisStart;
    }

    int finallyBodyEnd = finallyThrow;
    if (finallyBodyEnd - 1 >= finallyStart + 1) {
      IrOperationModel beforeThrow = operations.get(finallyBodyEnd - 1);
      if ("LOCAL_READ".equals(beforeThrow.kind()) && !beforeThrow.operands().isEmpty()
          && labelKey(finallyVariable).equals(labelKey(beforeThrow.operands().get(0)))) {
        finallyBodyEnd--;
      }
    }

    context.enterSuppressedTryHandlers(Set.of(finallyLabel));
    RenderResult tryBody;
    RenderResult catchBody;
    RenderResult finallyBody;
    try {
      tryBody = renderRange(operations, bodyStart, tryEnd, indent + 1, context, labels);
      catchBody = renderRange(
          operations, typedStart + 1, typedThrow + 1, indent + 1, context, labels);
      finallyBody = renderRange(
          operations, finallyStart + 1, finallyBodyEnd, indent + 1, context, labels);
    } finally {
      context.exitSuppressedTryHandlers();
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("try {\n").append(tryBody.source());
    source.append(indent(indent)).append("} catch (")
        .append(typedTypes.iterator().next()).append(" ").append(typedVariable)
        .append(") {\n").append(catchBody.source());
    source.append(indent(indent)).append("} finally {\n").append(finallyBody.source());
    source.append(indent(indent)).append("}\n");
    boolean complete = tryBody.complete() && catchBody.complete() && finallyBody.complete();
    return new ConditionalRender(source.toString(), complete, finallyThrow);
  }

  /**
   * Returns true when a typed handler only captures the primary exception and
   * rethrows it, which is the synthetic half of javac's try-with-resources
   * shape. A user catch clause (report/recover, return, etc.) is not this
   * shape and is rendered by {@link #renderUserCatchFinally}.
   */
  private static boolean isSimpleRethrowHandler(
      List<IrOperationModel> operations, int typedStart, int finallyBegin,
      String typedVariable) {
    int typedThrow = -1;
    for (int i = typedStart + 1; i < finallyBegin; i++) {
      IrOperationModel operation = operations.get(i);
      if ("THROW".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(typedVariable).equals(labelKey(operation.operands().get(0)))) {
        typedThrow = i;
        break;
      }
      if (!isSimpleRethrowOperation(operation)) return false;
    }
    if (typedThrow < 0) return false;
    for (int i = typedStart + 1; i < typedThrow; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LOCAL_WRITE".equals(operation.kind()) && operation.operands().size() >= 2
          && labelKey(typedVariable).equals(labelKey(operation.operands().get(1)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Renders a hand-written {@code try { ... } catch (T e) { ... } finally { ... }}
   * where javac duplicated the finally cleanup into the normal path and the
   * catch path. The cleanup is emitted once in the finally clause; the copies
   * are excluded by ending the try body at the normal end label and the catch
   * body at the start of the protected catch range's end label.
   */
  private static ConditionalRender renderUserCatchFinally(
      List<IrOperationModel> operations, int end, int indent,
      TranslationContext context, Map<String, Integer> labels,
      String startLabel, String endLabel, String typedHandlerLabel,
      String typedType, String typedVariable, String finallyLabel,
      Integer thisStart, int tryEnd, int typedStart, int finallyBegin) {
    if (thisStart == null) return null;

    // A nested protected region that starts at the outer end label is javac's
    // try-with-resources close region. That shape has its own synthetic cleanup
    // and is handled by the general renderer; claiming it here would clip the
    // close region at the outer end label.
    for (int i = 0; i < operations.size(); i++) {
      IrOperationModel candidate = operations.get(i);
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 4) continue;
      if (labelKey(endLabel).equals(labelKey(candidate.operands().get(0)))) return null;
    }

    String catchEndLabel = null;
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 5) continue;
      List<String> metadata = candidate.operands();
      if (labelKey(typedHandlerLabel).equals(labelKey(metadata.get(0)))
          && finallyLabel.equals(labelKey(metadata.get(2)))
          && "true".equals(metadata.get(4))) {
        catchEndLabel = metadata.get(1);
        break;
      }
    }
    if (catchEndLabel == null) return null;
    Integer catchEnd = labels.get(labelKey(catchEndLabel));
    Integer normalEnd = labels.get(labelKey(endLabel));
    if (catchEnd == null || normalEnd == null
        || catchEnd <= typedStart || catchEnd > finallyBegin
        || normalEnd <= tryEnd || normalEnd > catchEnd) {
      return null;
    }

    int bodyStart = thisStart + 1;
    while (bodyStart < tryEnd && isExceptionBoundary(operations.get(bodyStart))) bodyStart++;
    if (isBackwardBranchTarget(operations, thisStart, tryEnd, startLabel, labels)) {
      bodyStart = thisStart;
    }
    if (bodyStart >= normalEnd) return null;

    int finallyStart = finallyBegin + 1;
    while (finallyStart < end && isExceptionBoundary(operations.get(finallyStart))) {
      finallyStart++;
    }
    if (finallyStart >= end) return null;
    IrOperationModel finallyStore = operations.get(finallyStart);
    if (!"LOCAL_WRITE".equals(finallyStore.kind()) || finallyStore.operands().size() < 2
        || !"<exception>".equals(finallyStore.operands().get(1))) return null;
    String finallyVariable = finallyStore.operands().get(0);
    int finallyThrow = -1;
    for (int i = finallyStart + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CATCH_BEGIN".equals(operation.kind()) && operation.operands().size() >= 3) {
        Integer nestedStart = labels.get(labelKey(operation.operands().get(0)));
        if (nestedStart == null || nestedStart < thisStart) break;
      }
      if ("THROW".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(finallyVariable).equals(labelKey(operation.operands().get(0)))) {
        finallyThrow = i;
      }
    }
    if (finallyThrow < 0) return null;
    int finallyBodyEnd = finallyThrow;
    if (finallyBodyEnd - 1 >= finallyStart + 1) {
      IrOperationModel beforeThrow = operations.get(finallyBodyEnd - 1);
      if ("LOCAL_READ".equals(beforeThrow.kind()) && !beforeThrow.operands().isEmpty()
          && labelKey(finallyVariable).equals(labelKey(beforeThrow.operands().get(0)))) {
        finallyBodyEnd--;
      }
    }

    context.enterSuppressedTryHandlers(Set.of(finallyLabel));
    RenderResult tryBody;
    RenderResult catchBody;
    RenderResult finallyBody;
    try {
      // The normal path may jump to the region's end label, where javac placed
      // a duplicate finally cleanup. Rendering through that label drops the
      // jump; the cleanup is rendered once below.
      tryBody = renderRange(
          operations, bodyStart, normalEnd, indent + 1, context, labels);
      catchBody = renderRange(
          operations, typedStart + 1, catchEnd, indent + 1, context, labels);
      finallyBody = renderRange(
          operations, finallyStart + 1, finallyBodyEnd, indent + 1, context, labels);
    } finally {
      context.exitSuppressedTryHandlers();
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("try {\n").append(tryBody.source());
    source.append(indent(indent)).append("} catch (")
        .append(typedType).append(" ").append(typedVariable)
        .append(") {\n").append(catchBody.source());
    source.append(indent(indent)).append("} finally {\n").append(finallyBody.source());
    source.append(indent(indent)).append("}\n");
    boolean complete = tryBody.complete() && catchBody.complete() && finallyBody.complete();
    return new ConditionalRender(source.toString(), complete, finallyThrow);
  }

  private static boolean allExceptionMarkers(
      List<IrOperationModel> operations, int from, int to) {
    if (from < 0 || to > operations.size() || from > to) return false;
    for (int i = from; i < to; i++) {
      if (!isExceptionBoundary(operations.get(i))) return false;
    }
    return true;
  }

  private static boolean isBackwardBranchTarget(List<IrOperationModel> operations,
      int startIndex, int end, String label, Map<String, Integer> labels) {
    String key = labelKey(label);
    for (int i = startIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target == null || !key.equals(labelKey(target))) continue;
      Integer targetIndex = labels.get(key);
      if (targetIndex != null && targetIndex <= startIndex) return true;
    }
    return false;
  }

  private static boolean isExceptionBoundary(IrOperationModel operation) {
    return "LABEL".equals(operation.kind())
        || "TRY_BEGIN".equals(operation.kind())
        || "TRY_END".equals(operation.kind())
        || "CATCH_BEGIN".equals(operation.kind());
  }

  private static boolean isSimpleRethrowOperation(IrOperationModel operation) {
    return switch (operation.kind()) {
      case "LOCAL_READ", "LOCAL_WRITE", "CONSTANT", "LABEL", "TRY_END", "CATCH_BEGIN",
          "TRY_BEGIN", "DUP", "DROP" -> true;
      default -> false;
    };
  }

  /**
   * Structures a protected region. The grouped path renders every handler in
   * the exception table as its own catch clause; the merged path is the
   * historical single-handler rendering used when a region's markers are
   * interleaved with try-with-resources plumbing.
   */
  private static ConditionalRender renderTryCatch(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    ConditionalRender grouped = renderGroupedTryCatch(
        operations, begin, end, indent, context, labels);
    if (grouped != null) return grouped;
    ConditionalRender split = renderSplitExceptionRegions(
        operations, begin, end, indent, context, labels);
    if (split != null) return split;
    return renderMergedTryCatch(operations, begin, end, indent, context, labels);
  }

  private static boolean isSplitSwitchRegion(
      List<IrOperationModel> operations, int begin, int end, int tryEnd,
      String handlerLabel, String exceptionType, Map<String, Integer> labels) {
    Integer handlerIndex = labels.get(labelKey(handlerLabel));
    if (handlerIndex == null) return false;
    int matchingRanges = 0;
    boolean hasSwitch = false;
    for (int i = begin; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("TRY_BEGIN".equals(operation.kind())) {
        if (operation.operands().size() < 4
            || !labelKey(handlerLabel).equals(labelKey(operation.operands().get(2)))
            || !exceptionType.equals(operation.operands().get(3))) return false;
        matchingRanges++;
      }
      if ("TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) hasSwitch = true;
    }
    if (matchingRanges < 2 || !hasSwitch) return false;
    for (int i = begin + 1; i < tryEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"TABLE_SWITCH".equals(operation.kind())
          && !"LOOKUP_SWITCH".equals(operation.kind())) continue;
      for (int operand = 1; operand < operation.operands().size(); operand++) {
        Integer target = labels.get(labelKey(operation.operands().get(operand)));
        if (target != null && target > tryEnd && target < handlerIndex) return true;
      }
    }
    return false;
  }

  /**
   * javac's string switch emits a separate protected range around each case's
   * {@code GETSTATIC}, all sharing one handler. The switch therefore appears
   * to jump outside the first protected range. When every protected range in
   * the method shares one handler/type, merge them into a single try/catch and
   * render the body flat. This is deliberately narrow: it only claims a
   * method-tail handler with a switch in the body and no jumps past the
   * handler.
   */
  private static ConditionalRender renderSplitExceptionRegions(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    List<String> metadata = operations.get(begin).operands();
    if (metadata.size() < 4) return null;
    String startLabel = metadata.get(0);
    String handlerLabel = labelKey(metadata.get(2));
    String exceptionType = metadata.get(3);

    int tryBegins = 0;
    boolean hasSwitch = false;
    for (int i = begin; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("TRY_BEGIN".equals(operation.kind())) {
        if (operation.operands().size() < 4
            || !handlerLabel.equals(labelKey(operation.operands().get(2)))
            || !exceptionType.equals(operation.operands().get(3))) return null;
        tryBegins++;
      }
      if ("CATCH_BEGIN".equals(operation.kind()) && operation.operands().size() >= 4
          && !handlerLabel.equals(labelKey(operation.operands().get(2)))) return null;
      if ("TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) hasSwitch = true;
    }
    if (tryBegins < 2 || !hasSwitch) return null;

    Integer handlerIndex = labels.get(handlerLabel);
    Integer thisStart = labels.get(labelKey(startLabel));
    if (handlerIndex == null || thisStart == null
        || handlerIndex <= begin || handlerIndex >= end) return null;

    int bodyStart = thisStart + 1;
    while (bodyStart < handlerIndex && isExceptionBoundary(operations.get(bodyStart))) {
      bodyStart++;
    }
    if (bodyStart >= handlerIndex) return null;

    for (int i = bodyStart; i < handlerIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind())
          && !"CONDITIONAL_BRANCH".equals(operation.kind())) continue;
      for (int operand = 1; operand < operation.operands().size(); operand++) {
        Integer target = labels.get(labelKey(operation.operands().get(operand)));
        if (target != null && target > handlerIndex && target < end) return null;
      }
    }

    if (!isTerminal(operations.get(end - 1))) return null;
    int catchStart = handlerIndex + 1;
    while (catchStart < end && isExceptionBoundary(operations.get(catchStart))) {
      catchStart++;
    }
    if (catchStart >= end) return null;
    IrOperationModel store = operations.get(catchStart);
    if (!"LOCAL_WRITE".equals(store.kind()) || store.operands().size() < 2
        || !"<exception>".equals(store.operands().get(1))) return null;
    String exceptionVariable = store.operands().get(0);

    boolean previous = context.suppressesNestedTryRegions();
    context.setSuppressNestedTryRegions(true);
    RenderResult body;
    RenderResult catchBody;
    try {
      body = renderRange(
          operations, bodyStart, handlerIndex, indent + 1, context, labels);
      catchBody = renderRange(
          operations, catchStart + 1, end, indent + 1, context, labels);
    } finally {
      context.setSuppressNestedTryRegions(previous);
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("try {\n").append(body.source());
    source.append(indent(indent)).append("} catch (")
        .append(exceptionType).append(" ").append(exceptionVariable)
        .append(") {\n").append(catchBody.source());
    source.append(indent(indent)).append("}\n");
    return new ConditionalRender(
        source.toString(), body.complete() && catchBody.complete(), end - 1);
  }

  private static ConditionalRender renderMergedTryCatch(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    List<String> metadata = operations.get(begin).operands();
    if (metadata.size() < 4) return null;
    String startLabel = metadata.get(0);
    String endLabel = metadata.get(1);
    String handlerLabel = metadata.get(2);
    String exceptionType = metadata.get(3);
    Set<String> exceptionTypes = new java.util.LinkedHashSet<>();
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 4) continue;
      List<String> candidateMetadata = candidate.operands();
      if (labelKey(startLabel).equals(labelKey(candidateMetadata.get(0)))
          && labelKey(endLabel).equals(labelKey(candidateMetadata.get(1)))
          && labelKey(handlerLabel).equals(labelKey(candidateMetadata.get(2)))) {
        exceptionTypes.add(candidateMetadata.get(3));
      }
    }
    if (exceptionTypes.isEmpty()) exceptionTypes.add(exceptionType);
    int tryEnd = -1;
    int catchBegin = -1;
    for (int i = begin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (operation.operands().size() >= 3
          && labelKey(startLabel).equals(labelKey(operation.operands().get(0)))
          && labelKey(endLabel).equals(labelKey(operation.operands().get(1)))
          && labelKey(handlerLabel).equals(labelKey(operation.operands().get(2)))) {
        if ("TRY_END".equals(operation.kind())) tryEnd = i;
        if ("CATCH_BEGIN".equals(operation.kind())) catchBegin = i;
      }
    }
    if (tryEnd <= begin || catchBegin <= tryEnd) {
      // Some javac/ASM exception tables carry normalized labels that no
      // longer match the marker operands after nested-region reconstruction.
      // If the structural markers are still ordered, preserve the exception
      // boundary instead of discarding the whole region.
      tryEnd = -1;
      catchBegin = -1;
      for (int i = begin + 1; i < end; i++) {
        if (tryEnd < 0 && "TRY_END".equals(operations.get(i).kind())) tryEnd = i;
        if (tryEnd >= 0 && "CATCH_BEGIN".equals(operations.get(i).kind())) {
          catchBegin = i;
          break;
        }
      }
      if (tryEnd <= begin || catchBegin <= tryEnd) return null;
    }

    Set<String> regionHandlerLabels = new java.util.LinkedHashSet<>();
    for (IrOperationModel candidate : operations) {
      if (!"TRY_BEGIN".equals(candidate.kind()) || candidate.operands().size() < 4) continue;
      List<String> candidateMetadata = candidate.operands();
      if (labelKey(startLabel).equals(labelKey(candidateMetadata.get(0)))
          && labelKey(endLabel).equals(labelKey(candidateMetadata.get(1)))) {
        regionHandlerLabels.add(labelKey(candidateMetadata.get(2)));
      }
    }
    String normalJoinLabel = null;
    if (regionHandlerLabels.size() == 1) {
      for (int i = tryEnd + 1; i < catchBegin; i++) {
        IrOperationModel operation = operations.get(i);
        if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
        String candidateJoin = operation.operands().get(0);
        Integer candidateJoinIndex = labels.get(labelKey(candidateJoin));
        if (candidateJoinIndex != null && candidateJoinIndex > catchBegin) {
          normalJoinLabel = candidateJoin;
        }
      }
    }

    int handlerEnd = end;
    String joinLabel = null;
    for (int i = catchBegin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);

      // Nested close()/cleanup regions may have their own joins; structure them
      // first and don't mistake their internal branches for the outer handler exit.
      if ("TRY_BEGIN".equals(operation.kind())) {
        ConditionalRender nested = renderTryCatch(
            operations, i, end, indent, context, labels);
        if (nested != null) {
          i = nested.lastIndex();
          continue;
        }
      }

      if (normalJoinLabel != null) {
        if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty()
            && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
          handlerEnd = i;
          joinLabel = normalJoinLabel;
          break;
        }
        if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
            && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
          // A switch arm or nested block can jump to the shared join before the
          // handler's final instruction. That is an inner edge, not the end of
          // the handler: keep scanning for the actual join label so the whole
          // handler (including the switch) stays in the catch clause.
          Integer joinTarget = labels.get(labelKey(normalJoinLabel));
          if (joinTarget != null && joinTarget > i && joinTarget < end
              && joinTarget < operations.size()
              && "LABEL".equals(operations.get(joinTarget).kind())) {
            continue;
          }
          handlerEnd = i;
          joinLabel = normalJoinLabel;
          break;
        }
      } else if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        Integer targetIndex = labels.get(labelKey(target));
        Integer protectedEnd = labels.get(labelKey(endLabel));
        // Backward jumps are loop/continue edges inside the handler, not joins.
        if (targetIndex != null && targetIndex > i
            && protectedEnd != null && targetIndex > protectedEnd) {
          handlerEnd = i;
          joinLabel = target;
          break;
        }
      }
      if ("RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
          || "TERMINATE".equals(operation.kind())) {
        handlerEnd = i + 1;
        break;
      }
    }
    if (handlerEnd <= catchBegin + 1) return null;

    // Multiple exception-table entries can share a protected range and handler
    // (Java multi-catch). Start after the actual start label so adjacent
    // TRY_BEGIN metadata records are never mistaken for executable nested regions.
    Integer startIndex = labels.get(labelKey(startLabel));
    int bodyStart = startIndex == null ? begin + 1 : startIndex + 1;
    while (bodyStart < tryEnd
        && ("LABEL".equals(operations.get(bodyStart).kind())
            || "TRY_BEGIN".equals(operations.get(bodyStart).kind()))) bodyStart++;
    // A protected range can start exactly at a loop header (try inside the
    // loop body, or a try-with-resources body that is itself a loop). Keep the
    // header label so the body renderer can structure the back edge instead of
    // leaving it as an unresolved jump.
    if (startIndex != null && isBackwardBranchTarget(
        operations, startIndex, tryEnd, startLabel, labels)) {
      bodyStart = startIndex;
    }
    RenderResult tryBodyCore = renderRange(
        operations, bodyStart, tryEnd, indent + 1, context, labels);
    StringBuilder tryBodySource = new StringBuilder(tryBodyCore.source());
    boolean tryBodyComplete = tryBodyCore.complete();
    // The normal path often contains its return/cleanup immediately after
    // TRY_END. Keep that path inside the generated try block instead of
    // skipping it when the catch handler is consumed.
    if (regionHandlerLabels.size() == 1) {
      int normalCompletionEnd = catchBegin;
      if (normalJoinLabel != null) {
        for (int i = tryEnd + 1; i < catchBegin; i++) {
          IrOperationModel operation = operations.get(i);
          if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
              && labelKey(normalJoinLabel).equals(labelKey(operation.operands().get(0)))) {
            normalCompletionEnd = i;
          }
        }
      }
      if (normalCompletionEnd > tryEnd + 1
          && isSimpleNormalCompletion(operations, tryEnd + 1, normalCompletionEnd)) {
        RenderResult normalCompletion = renderRange(
            operations, tryEnd + 1, normalCompletionEnd, indent + 1, context, labels);
        tryBodySource.append(normalCompletion.source());
        tryBodyComplete &= normalCompletion.complete();
      }
    }

    int catchBodyStart = catchBegin + 1;
    String exceptionVariable = "caughtException";
    // A handler label is normally the first instruction after CATCH_BEGIN.
    // Skip labels before looking for the JVM's exception-stack-to-local store.
    while (catchBodyStart < handlerEnd
        && "LABEL".equals(operations.get(catchBodyStart).kind())) {
      catchBodyStart++;
    }
    if (catchBodyStart < handlerEnd) {
      IrOperationModel first = operations.get(catchBodyStart);
      if ("LOCAL_WRITE".equals(first.kind()) && first.operands().size() >= 2
          && "<exception>".equals(first.operands().get(1))) {
        exceptionVariable = first.operands().get(0);
        catchBodyStart++;
      }
    }
    RenderResult catchBody = renderRange(
        operations, catchBodyStart, handlerEnd, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("try {\n").append(tryBodySource);
    if (exceptionTypes.size() > 1) {
      // JVM multi-catch shares one handler for several exception-table entries.
      // JavaScript has one catch clause, so dispatch by runtime type and rethrow
      // exceptions that do not match any listed alternative.
      source.append(indent(indent)).append("} catch (caughtException) {\n");
      source.append(indent(indent + 1)).append("if (!(");
      int typeIndex = 0;
      for (String type : exceptionTypes) {
        if (typeIndex++ > 0) source.append(" || ");
        source.append("GoldClientRuntime.instanceOf(caughtException, \"")
            .append(escapeJava(type)).append("\")");
      }
      source.append(")) throw caughtException;\n");
      if (!"caughtException".equals(exceptionVariable)) {
        source.append(indent(indent + 1)).append("var ").append(exceptionVariable)
            .append(" = caughtException;\n");
      }
      source.append(catchBody.source());
    } else {
      source.append(indent(indent)).append("} catch (")
          .append(exceptionType).append(" ").append(exceptionVariable).append(") {\n")
          .append(catchBody.source());
    }
    source.append(indent(indent)).append("}\n");
    boolean complete = tryBodyComplete && catchBody.complete();
    // A handler may legitimately terminate with return/throw instead of
    // jumping to a shared continuation. That is structured control flow, not
    // evidence that the exception region was only partially rendered.
    if (joinLabel == null && handlerEnd < end
        && !isTerminal(operations.get(handlerEnd - 1))) complete = false;
    int last = joinLabel == null ? handlerEnd - 1 : handlerEnd;
    return new ConditionalRender(source.toString(), complete, last);
  }


  private static ConditionalRender renderSwitch(
      List<IrOperationModel> operations,
      int switchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel operation = operations.get(switchIndex);
    List<String> operands = operation.operands();
    String value;
    String defaultLabel;
    List<SwitchArm> arms = new java.util.ArrayList<>();

    try {
      if ("TABLE_SWITCH".equals(operation.kind())) {
        if (operands.size() < 4) return null;
        value = operands.get(0);
        int min = Integer.parseInt(operands.get(1));
        int max = Integer.parseInt(operands.get(2));
        defaultLabel = operands.get(3);
        if (max < min || (long) max - min + 1 != operands.size() - 4) return null;
        for (long key = min; key <= (long) max; key++) {
          arms.add(new SwitchArm((int) key, operands.get(4 + (int) (key - min))));
        }
      } else {
        if (operands.size() < 2 || (operands.size() - 2) % 2 != 0) return null;
        value = operands.get(0);
        defaultLabel = operands.get(1);
        for (int index = 2; index < operands.size(); index += 2) {
          arms.add(new SwitchArm(
              Integer.parseInt(operands.get(index)), operands.get(index + 1)));
        }
      }
    } catch (NumberFormatException exception) {
      return null;
    }

    Integer defaultIndex = labels.get(labelKey(defaultLabel));
    if (defaultIndex == null || defaultIndex <= switchIndex || defaultIndex > end) return null;
    Map<String, Integer> targetIndices = new HashMap<>();
    for (SwitchArm arm : arms) {
      Integer target = labels.get(labelKey(arm.label()));
      if (target == null || target <= switchIndex || target > end) return null;
      targetIndices.put(arm.label(), target);
    }

    int lastDistinctTarget = targetIndices.values().stream()
        .filter(index -> !index.equals(defaultIndex))
        .mapToInt(Integer::intValue).max().orElse(switchIndex);
    int lastTarget = Math.max(defaultIndex, lastDistinctTarget);

    // Targets equal to the render range's exclusive end are range exits: the
    // enclosing renderer owns the code after this range (a shared return block,
    // for example). The switch may only rely on falling out of its statement to
    // reach them, which is sound because an exit join consumes the range tail.
    int joinIndex = end;
    String joinLabel = null;
    boolean defaultIsJoin = false;
    if (defaultIndex == end) {
      defaultIsJoin = true;
    } else if (defaultIndex > lastDistinctTarget) {
      for (int index = switchIndex + 1; index < defaultIndex; index++) {
        IrOperationModel candidate = operations.get(index);
        if ("JUMP".equals(candidate.kind()) && !candidate.operands().isEmpty()
            && defaultIndex == labels.getOrDefault(labelKey(candidate.operands().get(0)), -1)) {
          defaultIsJoin = true;
          break;
        }
      }
    }
    if (defaultIsJoin) {
      joinIndex = defaultIndex;
      joinLabel = defaultLabel;
    }
    for (int index = switchIndex + 1; !defaultIsJoin && index < end; index++) {
      IrOperationModel candidate = operations.get(index);
      if (!"JUMP".equals(candidate.kind()) || candidate.operands().isEmpty()) continue;
      String targetLabel = candidate.operands().get(0);
      Integer targetIndex = labels.get(labelKey(targetLabel));
      // Only a forward jump can leave the switch: a jump back to a label inside
      // the switch is a loop edge that the segment renderer handles.
      if (targetIndex != null && targetIndex > index
          && targetIndex > lastTarget && targetIndex < joinIndex) {
        joinIndex = targetIndex;
        joinLabel = targetLabel;
      }
    }

    // An arm that jumps to the range exit can only be represented by falling
    // out of the switch statement, so it requires the exit to be the join.
    for (SwitchArm arm : arms) {
      if (targetIndices.get(arm.label()) == end && joinIndex != end) return null;
    }

    if (joinLabel == null && joinIndex < operations.size()
        && "LABEL".equals(operations.get(joinIndex).kind())
        && !operations.get(joinIndex).operands().isEmpty()) {
      // The join is the render range's exit. Cases that jump straight there
      // still have to leave the switch, so scope that label as a switch break
      // even though the code after it belongs to the enclosing renderer.
      joinLabel = operations.get(joinIndex).operands().get(0);
    }

    Map<Integer, List<String>> labelsAtTarget = new java.util.TreeMap<>();
    for (SwitchArm arm : arms) {
      int target = targetIndices.get(arm.label());
      // Labels for the join are unnecessary: unmatched and exiting cases fall
      // out of the switch statement and continue at the join naturally.
      if (target == joinIndex) continue;
      labelsAtTarget.computeIfAbsent(target, ignored -> new java.util.ArrayList<>())
          .add("case " + arm.key() + ":");
    }
    if (!defaultIsJoin && defaultIndex < end) {
      labelsAtTarget.computeIfAbsent(defaultIndex, ignored -> new java.util.ArrayList<>())
          .add("default:");
    }

    List<Integer> orderedTargets = new java.util.ArrayList<>(labelsAtTarget.keySet());
    orderedTargets.sort(Integer::compareTo);
    StringBuilder source = new StringBuilder();
    // A switch whose join can be reached from a nested switch or loop needs a
    // JS label: a plain break would leave the wrong scope. The labeled block is
    // only emitted when a join was found, so ordinary switches stay unchanged.
    String switchLabel = joinLabel == null
        ? null : context.newSwitchLabel(jsLabel(joinLabel));
    int base = indent;
    if (switchLabel != null) {
      source.append(indent(indent)).append(switchLabel).append(": {\n");
      base = indent + 1;
    }
    source.append(indent(base)).append("switch (")
        .append(normalizeExpression(value)).append(") {\n");
    boolean complete = true;

    if (joinLabel != null) context.enterSwitchJoin(labelKey(joinLabel), switchLabel);
    try {
      for (int block = 0; block < orderedTargets.size(); block++) {
        int targetIndex = orderedTargets.get(block);
        int segmentEnd = block + 1 < orderedTargets.size()
            ? orderedTargets.get(block + 1) : joinIndex;
        if (segmentEnd < targetIndex + 1) {
          complete = false;
          continue;
        }

        for (String label : labelsAtTarget.get(targetIndex)) {
          source.append(indent(base + 1)).append(label).append("\n");
        }

        int bodyEnd = segmentEnd;
        boolean jumpsToJoin = false;
        if (bodyEnd > targetIndex + 1) {
          IrOperationModel last = operations.get(bodyEnd - 1);
          if ("JUMP".equals(last.kind()) && !last.operands().isEmpty()
              && joinLabel != null
              && labelKey(joinLabel).equals(labelKey(last.operands().get(0)))) {
            bodyEnd--;
            jumpsToJoin = true;
          }
        }

        RenderResult body = renderRange(
            operations, targetIndex + 1, bodyEnd, base + 2, context, labels);
        source.append(body.source());
        complete &= body.complete();

        boolean endsAbruptly = bodyEnd > targetIndex + 1
            && (isTerminal(operations.get(bodyEnd - 1)) || jumpsToJoin);
        boolean fallsThroughToNextCase = block + 1 < orderedTargets.size()
            && segmentEnd == orderedTargets.get(block + 1)
            && !endsAbruptly;
        if (jumpsToJoin || (!fallsThroughToNextCase && !endsAbruptly)) {
          source.append(indent(base + 2)).append("break;\n");
        }
      }
    } finally {
      if (joinLabel != null) context.exitSwitchJoin();
    }

    source.append(indent(base)).append("}\n");
    if (switchLabel != null) source.append(indent(indent)).append("}\n");
    int lastIndex = joinIndex < end ? joinIndex - 1 : end - 1;
    return new ConditionalRender(source.toString(), complete, lastIndex);
  }

  /**
   * Checks whether the block skipped by a forward goto has any incoming edge
   * from outside that block. A block with no external entry is unreachable
   * after the goto and may be omitted from linear source output.
   */
  private static boolean isIsolatedForwardJump(
      List<IrOperationModel> operations, int jumpIndex, int targetIndex) {
    if (targetIndex <= jumpIndex) return false;

    Set<String> skippedLabels = new HashSet<>();
    for (int i = jumpIndex + 1; i < targetIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty()) {
        skippedLabels.add(labelKey(operation.operands().get(0)));
      }
    }
    if (skippedLabels.isEmpty()) return true;

    // Any incoming edge from before the goto or from the continuation after
    // the target makes at least part of the skipped block live.
    for (int i = 0; i < operations.size(); i++) {
      if (i > jumpIndex && i < targetIndex) continue;
      if (i == jumpIndex) continue;
      if (referencesAnyLabel(operations.get(i), skippedLabels)) return false;
    }
    return true;
  }

  private static List<String> referencedLabels(IrOperationModel operation) {
    List<String> operands = operation.operands();
    String kind = operation.kind();
    if ("JUMP".equals(kind)) {
      return operands.isEmpty() ? List.of() : List.of(operands.get(0));
    }
    if ("CONDITIONAL_BRANCH".equals(kind)) {
      return operands.size() < 2 ? List.of() : List.of(operands.get(1));
    }
    if ("TABLE_SWITCH".equals(kind)) {
      List<String> result = new java.util.ArrayList<>();
      for (int i = 3; i < operands.size(); i++) result.add(operands.get(i));
      return result;
    }
    if ("LOOKUP_SWITCH".equals(kind)) {
      List<String> result = new java.util.ArrayList<>();
      if (operands.size() > 1) result.add(operands.get(1));
      for (int i = 3; i < operands.size(); i += 2) result.add(operands.get(i));
      return result;
    }
    return List.of();
  }

  private static boolean referencesAnyLabel(
      IrOperationModel operation, Set<String> labels) {
    List<String> operands = operation.operands();
    String kind = operation.kind();

    if ("JUMP".equals(kind)) {
      return !operands.isEmpty() && labels.contains(labelKey(operands.get(0)));
    }
    if ("CONDITIONAL_BRANCH".equals(kind)) {
      return operands.size() > 1 && labels.contains(labelKey(operands.get(1)));
    }
    if ("TABLE_SWITCH".equals(kind)) {
      for (int i = 3; i < operands.size(); i++) {
        if (labels.contains(labelKey(operands.get(i)))) return true;
      }
      return false;
    }
    if ("LOOKUP_SWITCH".equals(kind)) {
      if (operands.size() > 1 && labels.contains(labelKey(operands.get(1)))) return true;
      for (int i = 3; i < operands.size(); i += 2) {
        if (labels.contains(labelKey(operands.get(i)))) return true;
      }
      return false;
    }
    if ("TRY_BEGIN".equals(kind) || "TRY_END".equals(kind)
        || "CATCH_BEGIN".equals(kind)) {
      int limit = Math.min(3, operands.size());
      for (int i = 0; i < limit; i++) {
        if (labels.contains(labelKey(operands.get(i)))) return true;
      }
    }
    return false;
  }

  /**
   * Returns true when an unconditional jump skips only labels before reaching
   * its destination. Such a jump is equivalent to ordinary fall-through.
   */
  private static boolean isFallthroughJump(
      List<IrOperationModel> operations, int jumpIndex, int targetIndex) {
    if (targetIndex <= jumpIndex) return false;
    for (int i = jumpIndex + 1; i < targetIndex; i++) {
      if (!"LABEL".equals(operations.get(i).kind())) return false;
    }
    return true;
  }

  private static boolean isTerminal(IrOperationModel operation) {
    return "RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
        || "TERMINATE".equals(operation.kind());
  }

  /** True when a branch before {@code index} targets executable code after it. */
  private static boolean hasForwardBranchPast(
      List<IrOperationModel> operations, int start, int index, int end,
      Map<String, Integer> labels) {
    for (int i = start; i < index; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target == null) continue;
      Integer targetIndex = labels.get(labelKey(target));
      if (targetIndex != null && targetIndex > index && targetIndex < end) return true;
    }
    return false;
  }

  /**
   * Lowers the common JVM short-circuit boolean/value-merge shape:
   * branch-to-true, evaluate a second condition, branch-to-false, then join
   * with an unknown stack phi. Early returns preserve branch order and side
   * effects without emitting a fake unknown return value.
   */
  private static ConditionalRender renderBooleanReturnChain(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel firstBranch = operations.get(branchIndex);
    if (firstBranch.operands().size() < 2) return null;
    Integer trueLabelIndex = labels.get(labelKey(firstBranch.operands().get(1)));
    if (trueLabelIndex == null || trueLabelIndex <= branchIndex || trueLabelIndex >= end) return null;

    int trueConstantIndex = nextExecutableIndex(operations, trueLabelIndex + 1, end);
    if (trueConstantIndex < 0 || !"CONSTANT".equals(operations.get(trueConstantIndex).kind())
        || !isTrueLikeConstant(operations.get(trueConstantIndex))) return null;
    int trueJumpIndex = nextExecutableIndex(operations, trueConstantIndex + 1, end);
    if (trueJumpIndex < 0 || !"JUMP".equals(operations.get(trueJumpIndex).kind())
        || operations.get(trueJumpIndex).operands().isEmpty()) return null;
    String joinLabel = operations.get(trueJumpIndex).operands().get(0);
    Integer joinIndex = labels.get(labelKey(joinLabel));
    if (joinIndex == null || joinIndex <= trueLabelIndex || joinIndex >= end) return null;

    // Conditionals between the first test and the join either return the
    // true/false constant, or feed a later condition (a nested ternary in an
    // argument). Only the constant-returning ones are guards; the rest stay in
    // the rendered prefix so their value merges keep working.
    List<Integer> trueBranches = new java.util.ArrayList<>();
    List<Integer> falseBranches = new java.util.ArrayList<>();
    List<Integer> valueBranches = new java.util.ArrayList<>();
    Integer falseLabelIndex = null;
    for (int i = branchIndex; i < joinIndex; i++) {
      IrOperationModel candidate = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(candidate.kind())) continue;
      if (candidate.operands().size() < 2) return null;
      Integer target = labels.get(labelKey(candidate.operands().get(1)));
      if (target == null) return null;
      if (target.equals(trueLabelIndex)) {
        trueBranches.add(i);
      } else if (target < trueLabelIndex) {
        // Interleaved value-producing branch used by a later condition.
        valueBranches.add(i);
      } else if (target < joinIndex) {
        if (falseLabelIndex == null) falseLabelIndex = target;
        else if (!falseLabelIndex.equals(target)) return null;
        falseBranches.add(i);
      } else {
        return null;
      }
    }
    if (trueBranches.isEmpty() || !trueBranches.get(0).equals(branchIndex)
        || falseLabelIndex == null) return null;

    int falseConstantIndex = nextExecutableIndex(operations, falseLabelIndex + 1, end);
    if (falseConstantIndex < 0 || !"CONSTANT".equals(operations.get(falseConstantIndex).kind())
        || !isFalseLikeConstant(operations.get(falseConstantIndex))) return null;
    for (int i = falseConstantIndex + 1; i < joinIndex; i++) {
      if (!isLoopExitMetadata(operations.get(i))) return null;
    }
    int sinkIndex = nextExecutableIndex(operations, joinIndex + 1, end);
    if (sinkIndex < 0 || operations.get(sinkIndex).operands().isEmpty()
        || !"<unknown>".equals(operations.get(sinkIndex).operands().get(
            operations.get(sinkIndex).operands().size() - 1))) {
      return null;
    }

    String trueValue = operations.get(trueConstantIndex).operands().get(0);
    String falseValue = operations.get(falseConstantIndex).operands().get(0);
    StringBuilder source = new StringBuilder();
    boolean complete = true;
    if ("RETURN".equals(operations.get(sinkIndex).kind())) {
      List<Integer> guards = new java.util.ArrayList<>();
      guards.addAll(trueBranches);
      guards.addAll(falseBranches);
      guards.sort(Integer::compareTo);
      int previous = branchIndex;
      for (int guard : guards) {
        String guardCondition = operations.get(guard).operands().get(0);
        int prefixEnd = guard;
        List<Integer> pendingValues = new java.util.ArrayList<>();
        for (int valueBranch : valueBranches) {
          if (valueBranch > previous && valueBranch < guard) pendingValues.add(valueBranch);
        }
        if (!pendingValues.isEmpty()) {
          // Fold each interleaved value branch into the condition that consumes
          // its <unknown> marker instead of evaluating the same condition once
          // in an empty if/else and again in the guard expression.
          for (int valueBranch : pendingValues) {
            String merged = conditionalMergeExpression(
                operations, valueBranch, guard + 1, labels);
            if (merged == null) return null;
            int marker = guardCondition.indexOf("<unknown>");
            if (marker < 0) return null;
            guardCondition = guardCondition.substring(0, marker) + merged
                + guardCondition.substring(marker + "<unknown>".length());
          }
          prefixEnd = pendingValues.get(0);
          for (int j = prefixEnd; j < guard; j++) {
            IrOperationModel skipped = operations.get(j);
            if (!isExpressionOnly(skipped) && !isBranchValueMetadata(skipped)
                && !"JUMP".equals(skipped.kind())
                && !pendingValues.contains(j)) {
              return null;
            }
          }
        }
        if (guard != branchIndex) {
          RenderResult prefix = renderRange(
              operations, previous + 1, prefixEnd, indent, context, labels);
          source.append(prefix.source());
          complete &= prefix.complete();
        }
        boolean returnsTrue = trueBranches.contains(guard);
        source.append(indent(indent)).append("if (")
            .append(normalizeCondition(guardCondition))
            .append(") return ")
            .append(returnsTrue ? trueValue : falseValue).append(";\n");
        previous = guard;
      }
      source.append(indent(indent)).append("return ").append(trueValue).append(";\n");
      return new ConditionalRender(source.toString(), complete, sinkIndex);
    }
    if (!"LOCAL_WRITE".equals(operations.get(sinkIndex).kind())
        || operations.get(sinkIndex).operands().size() < 2
        || trueBranches.size() != 1 || falseBranches.size() != 1) return null;

    int falseBranch = falseBranches.get(0);
    RenderResult prefix = renderRange(
        operations, branchIndex + 1, falseBranch, indent + 1, context, labels);
    String local = operations.get(sinkIndex).operands().get(0);
    boolean declare = context.declareLocal(local);
    if (declare) source.append(indent(indent)).append("var ").append(local).append(";\n");
    source.append(indent(indent)).append("if (")
        .append(normalizeCondition(firstBranch.operands().get(0))).append(") {\n")
        .append(indent(indent + 1)).append(local).append(" = ").append(trueValue).append(";\n")
        .append(indent(indent)).append("} else {\n")
        .append(prefix.source())
        .append(indent(indent + 1)).append("if (")
        .append(normalizeCondition(operations.get(falseBranch).operands().get(0)))
        .append(") {\n")
        .append(indent(indent + 2)).append(local).append(" = ").append(falseValue).append(";\n")
        .append(indent(indent + 1)).append("} else {\n")
        .append(indent(indent + 2)).append(local).append(" = ").append(trueValue).append(";\n")
        .append(indent(indent + 1)).append("}\n")
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), prefix.complete(), sinkIndex);
  }

  private static String conditionalMergeExpression(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    if (branch.operands().size() < 2) return null;
    Integer targetIndex = labels.get(labelKey(branch.operands().get(1)));
    if (targetIndex == null || targetIndex <= branchIndex || targetIndex >= end) return null;
    int jumpIndex = findForwardJoinJump(
        operations, branchIndex + 1, targetIndex, end, labels);
    if (jumpIndex < 0 || jumpIndex >= targetIndex) return null;
    IrOperationModel jump = operations.get(jumpIndex);
    if (jump.operands().isEmpty()) return null;
    Integer joinIndex = labels.get(labelKey(jump.operands().get(0)));
    if (joinIndex == null || joinIndex <= targetIndex || joinIndex >= end) return null;
    String fallthroughValue = lastExpressionFromRange(
        operations, branchIndex + 1, jumpIndex);
    String targetValue = lastExpressionFromRange(
        operations, targetIndex + 1, joinIndex);
    if (fallthroughValue == null || targetValue == null) return null;
    return "(" + normalizeCondition(branch.operands().get(0)) + " ? "
        + targetValue + " : " + fallthroughValue + ")";
  }

  private static int nextExecutableIndex(
      List<IrOperationModel> operations, int start, int end) {
    for (int i = start; i < end; i++) {
      if (isLoopExitMetadata(operations.get(i))) continue;
      return i;
    }
    return -1;
  }

  private static boolean isTrueLikeConstant(IrOperationModel operation) {
    return !operation.operands().isEmpty()
        && ("1".equals(operation.operands().get(0)) || "true".equals(operation.operands().get(0)));
  }

  private static boolean isFalseLikeConstant(IrOperationModel operation) {
    return !operation.operands().isEmpty()
        && ("0".equals(operation.operands().get(0)) || "false".equals(operation.operands().get(0)));
  }

  /**
   * Reconstructs a stack phi used by a local write, return, or the first
   * side-effecting consumer after a simple conditional expression. Both paths
   * must be expression-only; control flow or side effects in the paths are
   * deliberately rejected rather than dropped.
   */
  /**
   * Structures a two-condition chain whose true and false destinations join
   * after two statement blocks. This is the common JVM shape for an if /
   * else-if / else that assigns the same local in each branch.
   */
  private static ConditionalRender renderConditionalValueChain(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel firstBranch = operations.get(branchIndex);
    if (firstBranch.operands().size() < 2) return null;
    Integer firstTargetIndex = labels.get(labelKey(firstBranch.operands().get(1)));
    if (firstTargetIndex == null || firstTargetIndex <= branchIndex || firstTargetIndex >= end) return null;

    int secondBranchIndex = -1;
    for (int i = branchIndex + 1; i < firstTargetIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind())) return null;
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (secondBranchIndex >= 0 || operation.operands().size() < 2) return null;
        secondBranchIndex = i;
      }
    }
    if (secondBranchIndex < 0) return null;
    Integer secondTargetIndex = labels.get(
        labelKey(operations.get(secondBranchIndex).operands().get(1)));
    if (secondTargetIndex == null || secondTargetIndex <= firstTargetIndex
        || secondTargetIndex >= end) return null;

    int joinJumpIndex = findForwardJoinJump(
        operations, firstTargetIndex + 1, secondTargetIndex, end, labels);
    if (joinJumpIndex < 0 || !onlyMetadataBetween(operations, joinJumpIndex + 1, secondTargetIndex))
      return null;
    IrOperationModel joinJump = operations.get(joinJumpIndex);
    if (joinJump.operands().isEmpty()) return null;
    Integer joinIndex = labels.get(labelKey(joinJump.operands().get(0)));
    if (joinIndex == null || joinIndex <= secondTargetIndex || joinIndex >= end) return null;

    for (int i = firstTargetIndex + 1; i < joinJumpIndex; i++) {
      if ("JUMP".equals(operations.get(i).kind())
          || "CONDITIONAL_BRANCH".equals(operations.get(i).kind())) return null;
    }
    for (int i = secondTargetIndex + 1; i < joinIndex; i++) {
      if ("JUMP".equals(operations.get(i).kind())
          || "CONDITIONAL_BRANCH".equals(operations.get(i).kind())) return null;
    }

    RenderResult prefix = renderRange(
        operations, branchIndex + 1, secondBranchIndex, indent + 1, context, labels);
    RenderResult firstBody = renderRange(
        operations, firstTargetIndex + 1, joinJumpIndex, indent + 1, context, labels);
    RenderResult secondBody = renderRange(
        operations, secondTargetIndex + 1, joinIndex, indent + 2, context, labels);
    RenderResult alternateFirstBody = renderRange(
        operations, firstTargetIndex + 1, joinJumpIndex, indent + 2, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (")
        .append(normalizeCondition(firstBranch.operands().get(0))).append(") {\n")
        .append(firstBody.source())
        .append(indent(indent)).append("} else {\n")
        .append(prefix.source())
        .append(indent(indent + 1)).append("if (")
        .append(normalizeCondition(operations.get(secondBranchIndex).operands().get(0)))
        .append(") {\n")
        .append(secondBody.source())
        .append(indent(indent + 1)).append("} else {\n")
        .append(alternateFirstBody.source())
        .append(indent(indent + 1)).append("}\n")
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(),
        prefix.complete() && firstBody.complete() && secondBody.complete()
            && alternateFirstBody.complete(),
        joinIndex - 1);
  }

  /**
   * Structures condition chains that converge on a shared then/else pair.
   * javac lowers {@code if (a || b) { ... } else { ... }} so that several
   * conditional edges at the top lead to two blocks: the then block (reached
   * when no condition holds, since it sits right after the chain) and the else
   * block. The then block ends with a jump to the join after the else block.
   * Every condition folds into one guard expression that preserves evaluation
   * order, and both blocks render once as an if/else pair.
   */
  /**
   * Structures a condition chain that selects between two shared blocks.
   * javac can route several short-circuit conditions through intermediate
   * labels before converging on one "then" block and one "else" block; the
   * first condition does not have to target the then block directly (as
   * {@link #renderChainIfElse} assumes). Every path in the chain is reduced to
   * an explicit boolean expression over the two block entries, and both blocks
   * are rendered once. The blocks must rejoin at one tail label, so a jump
   * leaving either block is still observable as the end of the if/else.
   */
  private static ConditionalRender renderTwoBlockDecisionChain(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    if (!"CONDITIONAL_BRANCH".equals(operations.get(branchIndex).kind())
        || operations.get(branchIndex).operands().size() < 2) {
      return null;
    }

    // Walk the contiguous condition work. It may contain labels and pure
    // expressions; any jump, return, throw, switch, try marker, or effectful
    // operation ends the chain work.
    List<Integer> conditionals = new java.util.ArrayList<>();
    int scan = branchIndex;
    while (scan < end) {
      IrOperationModel operation = operations.get(scan);
      if ("LABEL".equals(operation.kind())) {
        scan++;
        continue;
      }
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (operation.operands().size() < 2) return null;
        conditionals.add(scan);
        scan++;
        continue;
      }
      if (isExpressionOnly(operation) && !"DROP".equals(operation.kind())
          && !"DUP".equals(operation.kind()) && !"SWAP".equals(operation.kind())
          && !"STACK_OPERATION".equals(operation.kind())) {
        scan++;
        continue;
      }
      break;
    }
    if (conditionals.isEmpty() || conditionals.get(0) != branchIndex) return null;
    // The chain has to fall through into its "then" block, so it cannot end in
    // a control transfer of its own.
    if (scan >= end) return null;
    if (List.of("JUMP", "RETURN", "THROW", "TABLE_SWITCH", "LOOKUP_SWITCH",
        "TRY_BEGIN", "TRY_END", "CATCH_BEGIN").contains(operations.get(scan).kind())) {
      return null;
    }

    int thenIndex = scan - 1;
    while (thenIndex > branchIndex
        && !"LABEL".equals(operations.get(thenIndex).kind())) {
      thenIndex--;
    }
    if (thenIndex <= branchIndex || !"LABEL".equals(operations.get(thenIndex).kind())) {
      return null;
    }

    Integer elseIndex = null;
    for (int conditional : conditionals) {
      Integer target = labels.get(labelKey(
          operations.get(conditional).operands().get(1)));
      if (target == null || target <= branchIndex || target >= end) return null;
      if (target < thenIndex) continue;
      if (target == thenIndex) continue;
      if (elseIndex == null) elseIndex = target;
      else if (!elseIndex.equals(target)) return null;
    }
    if (elseIndex == null || elseIndex <= thenIndex || elseIndex >= end) return null;

    // A TRY_BEGIN marker is emitted before the label whose block it protects.
    // Both arms have to start at the marker, otherwise a try/catch that begins
    // the arm is rendered as plain code and its handler becomes unreachable.
    int thenStart = inlinedBlockStart(operations, thenIndex, labels);
    int elseStart = inlinedBlockStart(operations, elseIndex, labels);

    // The then block ends in a jump to the common join, or terminates. The
    // join must be past the else block so both blocks are rendered once.
    int thenTailIndex = -1;
    for (int i = thenStart; i < elseStart; i++) {
      if (!isBranchValueMetadata(operations.get(i))) thenTailIndex = i;
    }
    if (thenTailIndex < 0) return null;
    IrOperationModel thenTail = operations.get(thenTailIndex);
    int joinIndex;
    boolean thenTailJump;
    if ("JUMP".equals(thenTail.kind()) && !thenTail.operands().isEmpty()) {
      Integer target = labels.get(labelKey(thenTail.operands().get(0)));
      if (target == null || target <= elseIndex) return null;
      joinIndex = target;
      thenTailJump = true;
    } else if ("RETURN".equals(thenTail.kind()) || "THROW".equals(thenTail.kind())) {
      joinIndex = end;
      thenTailJump = false;
    } else {
      return null;
    }

    // The join may sit past this render range when the enclosing structure owns
    // the code between the two arms' exits. In that case both arms have to end
    // in a jump to the same join, and only the portion up to the range end is
    // rendered here.
    int consumedEnd = Math.min(joinIndex, end);
    boolean joinBeyondRange = joinIndex > end;
    int elseTailIndex = -1;
    boolean elseJumpAtRangeEnd = false;
    if (joinBeyondRange) {
      if (!thenTailJump) return null;
      for (int i = elseStart; i < end; i++) {
        if (!isBranchValueMetadata(operations.get(i))) elseTailIndex = i;
      }
      if (elseTailIndex < 0) return null;
      IrOperationModel elseTail = operations.get(elseTailIndex);
      Integer elseTarget = "JUMP".equals(elseTail.kind()) && !elseTail.operands().isEmpty()
          ? labels.get(labelKey(elseTail.operands().get(0))) : null;
      if (elseTarget != null && elseTarget.equals(joinIndex)) {
        // The else arm ends with its own jump to the join inside this range.
      } else {
        // The enclosing structure may have chosen `end` as the exclusive end
        // of this arm, leaving the arm's joining jump just past the range. It
        // owns that jump, so render only up to the range end.
        if (end >= operations.size()) return null;
        IrOperationModel rangeEnd = operations.get(end);
        if (!"JUMP".equals(rangeEnd.kind()) || rangeEnd.operands().isEmpty()) return null;
        Integer rangeEndTarget = labels.get(labelKey(rangeEnd.operands().get(0)));
        if (rangeEndTarget == null || !rangeEndTarget.equals(joinIndex)) return null;
        elseJumpAtRangeEnd = true;
      }
    }

    // Neither block may enter the chain or the other block; both may only jump
    // to the join or stay inside themselves.
    for (int i = branchIndex; i < elseStart; i++) {
      if (i == thenTailIndex && thenTailJump) continue;
      String target = branchTarget(operations.get(i));
      if (target == null) continue;
      Integer targetIndex = labels.get(labelKey(target));
      if (targetIndex == null) return null;
      if (i < thenIndex) {
        if (targetIndex != thenIndex && targetIndex != elseIndex
            && (targetIndex < branchIndex || targetIndex >= thenIndex)) return null;
      } else if (targetIndex < thenIndex || targetIndex >= elseIndex) {
        // The then block may only leave to the join; the join tail jump is
        // handled above.
        if (!(i >= thenIndex && targetIndex == joinIndex && joinIndex > elseIndex)) {
          return null;
        }
      }
    }
    for (int i = elseStart; i < consumedEnd; i++) {
      if (joinBeyondRange && i == elseTailIndex) continue;
      String target = branchTarget(operations.get(i));
      if (target == null) continue;
      Integer targetIndex = labels.get(labelKey(target));
      if (targetIndex == null) return null;
      if (targetIndex < elseIndex && targetIndex >= branchIndex) return null;
      if (targetIndex > joinIndex) return null;
    }
    // No instruction outside the consumed region may enter it.
    for (int i = 0; i < operations.size(); i++) {
      if (i >= branchIndex && i < consumedEnd) continue;
      String target = branchTarget(operations.get(i));
      if (target == null) continue;
      Integer targetIndex = labels.get(labelKey(target));
      if (targetIndex != null && targetIndex >= branchIndex
          && targetIndex < consumedEnd) return null;
    }

    Map<Integer, String> memo = new java.util.HashMap<>();
    String reachesThen = decisionChainReach(
        operations, branchIndex, thenIndex, elseIndex,
        labels, memo, new java.util.HashSet<>(), 0);
    if (reachesThen == null) return null;

    RenderResult thenBody = renderRange(
        operations, thenStart, thenTailJump ? thenTailIndex : elseStart,
        indent + 1, context, labels);
    RenderResult elseBody = renderRange(
        operations, elseStart,
        joinBeyondRange ? (elseJumpAtRangeEnd ? end : elseTailIndex) : joinIndex,
        indent + 1, context, labels);
    if (thenBody.complete() && elseBody.complete()
        && thenBody.source().isBlank() && elseBody.source().isBlank()) {
      return null;
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (").append(reachesThen).append(") {\n")
        .append(thenBody.source())
        .append(indent(indent)).append("} else {\n")
        .append(elseBody.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(),
        thenBody.complete() && elseBody.complete(),
        joinBeyondRange ? end - 1 : joinIndex - 1);
  }

  private static String decisionChainReach(
      List<IrOperationModel> operations,
      int index,
      int thenIndex,
      int elseIndex,
      Map<String, Integer> labels,
      Map<Integer, String> memo,
      java.util.Set<Integer> active,
      int depth) {
    if (depth > 32) return null;
    if (index >= thenIndex) return "true";
    String cached = memo.get(index);
    if (cached != null) return cached;
    if (!active.add(index)) return null;
    IrOperationModel operation = operations.get(index);
    String reach;
    if ("LABEL".equals(operation.kind())
        || (isExpressionOnly(operation) && !"DROP".equals(operation.kind())
            && !"DUP".equals(operation.kind()) && !"SWAP".equals(operation.kind())
            && !"STACK_OPERATION".equals(operation.kind()))) {
      reach = decisionChainReach(
          operations, index + 1, thenIndex, elseIndex, labels, memo, active, depth + 1);
    } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
        && operation.operands().size() >= 2) {
      Integer targetIndex = labels.get(labelKey(operation.operands().get(1)));
      if (targetIndex == null) return null;
      String taken;
      if (targetIndex == thenIndex) {
        taken = "true";
      } else if (targetIndex == elseIndex) {
        taken = "false";
      } else if (targetIndex > index && targetIndex < thenIndex) {
        taken = decisionChainReach(
            operations, targetIndex, thenIndex, elseIndex, labels, memo, active, depth + 1);
      } else {
        taken = null;
      }
      String fallthrough = decisionChainReach(
          operations, index + 1, thenIndex, elseIndex, labels, memo, active, depth + 1);
      if (taken == null || fallthrough == null) return null;
      String condition = normalizeCondition(operation.operands().get(0));
      if (taken.equals(fallthrough)) {
        reach = taken;
      } else if ("true".equals(taken) && "false".equals(fallthrough)) {
        reach = "(" + condition + ")";
      } else if ("false".equals(taken) && "true".equals(fallthrough)) {
        reach = "!(" + condition + ")";
      } else {
        reach = "(" + condition + " ? " + taken + " : " + fallthrough + ")";
      }
    } else {
      return null;
    }
    active.remove(index);
    memo.put(index, reach);
    return reach;
  }

  private record ValueRegionResult(List<String> values, String lastExpression, int stopIndex) {}

  /**
   * Structures a stack value that is materialized by several nested branch
   * merges. javac lowers compound boolean expressions such as
   * {@code a || (b == c)} into a chain of guards whose arms push constants and
   * whose intermediate results feed a later condition. The IR records those
   * intermediate stack values as {@code <unknown>} placeholders, so a
   * single-ternary fold cannot rebuild the expression. This walk evaluates the
   * whole value region, turning every branch into a ternary and substituting
   * the pending merge values into the conditions that consume them in stack
   * order. It only fires when the earlier, cheaper renderers could not resolve
   * the shape, and it rejects any region that is not a pure value DAG.
   */
  private static ConditionalRender renderBranchValueDag(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    boolean[] consumedConditionValue = {false};
    ValueRegionResult region = evaluateValueRegion(
        operations, branchIndex, end, labels, new ArrayList<>(),
        consumedConditionValue, 0);
    if (region == null) return null;
    // Only take this route when a merge value actually feeds a later condition.
    // Simple constant chains already have dedicated guard renderers, and those
    // shapes are intentionally lowered as early-return guards.
    if (!consumedConditionValue[0] || region.values().isEmpty()) return null;
    int sinkIndex = region.stopIndex();
    if (sinkIndex <= branchIndex || sinkIndex >= end) return null;
    IrOperationModel sink = operations.get(sinkIndex);
    // Return merges keep the existing guard-style lowering, which is easier to
    // read and already covers simple chains; this route is for stack values
    // that feed a field/local write or another condition.
    if ("RETURN".equals(sink.kind())) return null;
    if (!List.of("FIELD_WRITE", "LOCAL_WRITE", "METHOD_CALL", "CALL_VALUE",
        "CONDITIONAL_BRANCH").contains(sink.kind())) return null;
    long unknownCount = sink.operands().stream()
        .mapToLong(value -> occurrences(value, "<unknown>")).sum();
    if (unknownCount != region.values().size()) return null;
    List<String> replaced = substituteUnknowns(sink.operands(), region.values());
    if (replaced == null) return null;
    if ("CONDITIONAL_BRANCH".equals(sink.kind())) {
      if (replaced.isEmpty()) return null;
      return renderConditional(
          operations, sinkIndex, end, indent, context, labels, replaced.get(0));
    }
    String lowered = translateOperation(new IrOperationModel(
        sink.kind(), replaced, sink.sourceInstruction()), context);
    if (lowered == null || lowered.isBlank()) return null;
    return new ConditionalRender(
        indent(indent) + lowered + "\n", true, sinkIndex);
  }

  private static ValueRegionResult evaluateValueRegion(
      List<IrOperationModel> operations,
      int start,
      int end,
      Map<String, Integer> labels,
      List<String> pending,
      boolean[] consumedConditionValue,
      int depth) {
    if (depth > 64) return null;
    String lastExpression = null;
    int index = start;
    while (index < end) {
      IrOperationModel operation = operations.get(index);
      String kind = operation.kind();
      if ("LABEL".equals(kind)) {
        index++;
        continue;
      }
      if ("JUMP".equals(kind)) {
        // A jump that ends this arm at its join is fine; anything else leaves
        // the value region and cannot be folded into an expression.
        if (operation.operands().isEmpty()) return null;
        Integer target = labels.get(labelKey(operation.operands().get(0)));
        if (target == null || target != end) return null;
        return new ValueRegionResult(List.copyOf(pending), lastExpression, index);
      }
      if ("CONDITIONAL_BRANCH".equals(kind)) {
        if (operation.operands().size() < 2) return null;
        Integer taken = labels.get(labelKey(operation.operands().get(1)));
        if (taken == null || taken <= index || taken >= end) return null;
        int join = findBranchJoin(operations, index, taken, end, labels);
        if (join <= index || join > end) return null;
        String condition = operation.operands().get(0);
        int needed = occurrences(condition, "<unknown>");
        if (needed > 0) {
          consumedConditionValue[0] = true;
          if (needed > pending.size()) return null;
          List<String> used = new ArrayList<>(pending.subList(0, needed));
          List<String> substituted = substituteUnknowns(
              List.of(condition), used);
          if (substituted == null) return null;
          condition = substituted.get(0);
          pending.subList(0, needed).clear();
        }
        String fallthroughValue = evaluateArmValue(
            operations, index + 1, join, labels, consumedConditionValue, depth + 1);
        String takenValue = evaluateArmValue(
            operations, taken, join, labels, consumedConditionValue, depth + 1);
        if (fallthroughValue == null || takenValue == null) return null;
        pending.add("(" + normalizeCondition(condition) + " ? "
            + takenValue + " : " + fallthroughValue + ")");
        index = join;
        continue;
      }
      if (isExpressionOnly(operation) && !"DROP".equals(kind)
          && !"STACK_OPERATION".equals(kind) && !"DUP".equals(kind)
          && !"SWAP".equals(kind)) {
        String expression = expressionFromOperation(operation);
        if (expression != null) lastExpression = expression;
        index++;
        continue;
      }
      return new ValueRegionResult(List.copyOf(pending), lastExpression, index);
    }
    return new ValueRegionResult(List.copyOf(pending), lastExpression, index);
  }

  private static String evaluateArmValue(
      List<IrOperationModel> operations,
      int start,
      int join,
      Map<String, Integer> labels,
      boolean[] consumedConditionValue,
      int depth) {
    if (start >= join) return null;
    ValueRegionResult result = evaluateValueRegion(
        operations, start, join, labels, new ArrayList<>(), consumedConditionValue, depth);
    if (result == null || result.stopIndex() > join) return null;
    if (!result.values().isEmpty()) {
      return result.values().get(result.values().size() - 1);
    }
    return result.lastExpression();
  }

  /**
   * Finds where the two arms of a conditional rejoin. javac emits either the
   * fall-through arm or the taken arm with the joining jump, so try the
   * backward jump scan first and then look inside the taken block.
   */
  private static int findBranchJoin(
      List<IrOperationModel> operations,
      int branchIndex,
      int takenIndex,
      int end,
      Map<String, Integer> labels) {
    int jumpIndex = findForwardJoinJump(
        operations, branchIndex + 1, takenIndex, end, labels);
    if (jumpIndex >= 0) {
      Integer join = labels.get(labelKey(operations.get(jumpIndex).operands().get(0)));
      if (join != null && join > takenIndex && join <= end) return join;
    }
    for (int i = takenIndex; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind())) {
        if (operation.operands().isEmpty()) return -1;
        Integer join = labels.get(labelKey(operation.operands().get(0)));
        return join != null && join > takenIndex && join <= end ? join : -1;
      }
      if ("RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
          || "TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) return -1;
    }
    return -1;
  }

  private static ConditionalRender renderChainIfElse(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel firstBranch = operations.get(branchIndex);
    if (firstBranch.operands().size() < 2) return null;
    Integer thenIndex = labels.get(labelKey(firstBranch.operands().get(1)));
    if (thenIndex == null || thenIndex <= branchIndex || thenIndex >= end) return null;

    List<String> chainConditions = new java.util.ArrayList<>();
    List<Boolean> chainTargetsThen = new java.util.ArrayList<>();
    chainConditions.add(firstBranch.operands().get(0));
    chainTargetsThen.add(true);
    int elseIndex = -1;
    for (int i = branchIndex + 1; i < thenIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (operation.operands().size() < 2) return null;
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null || target <= i || target >= end) return null;
        if (target.equals(thenIndex)) {
          chainConditions.add(operation.operands().get(0));
          chainTargetsThen.add(true);
        } else {
          if (elseIndex < 0) {
            if (target < thenIndex) return null;
            elseIndex = target;
          } else if (!target.equals(elseIndex)) {
            return null;
          }
          chainConditions.add(operation.operands().get(0));
          chainTargetsThen.add(false);
        }
      } else if (isBranchValueMetadata(operation)) {
        continue;
      } else if (isExpressionOnly(operation) && !"DROP".equals(operation.kind())) {
        continue;
      } else {
        return null;
      }
    }
    if (elseIndex < 0 || thenIndex > elseIndex) return null;

    // The then block is the region between the then target and the else target.
    // It has to leave that region through a jump past the else block, or by
    // ending the method. Its interior is structured normally, so nested
    // branches inside the then block are not part of the condition chain.
    int thenTailIndex = -1;
    for (int i = thenIndex + 1; i < elseIndex; i++) {
      if (!isBranchValueMetadata(operations.get(i))) thenTailIndex = i;
    }
    if (thenTailIndex < 0) return null;
    IrOperationModel thenTail = operations.get(thenTailIndex);
    int thenJumpIndex = -1;
    int terminalThenIndex = -1;
    Integer joinIndex = null;
    boolean joinBeyondRange = false;
    if ("JUMP".equals(thenTail.kind()) && !thenTail.operands().isEmpty()) {
      String jumpTarget = thenTail.operands().get(0);
      Integer target = labels.get(labelKey(jumpTarget));
      if (target == null || target <= elseIndex) return null;
      // A join at or beyond the range end is the enclosing switch's join:
      // both arms leave the switch statement and its trailing break handles
      // the jump.
      if (target >= end) {
        if (target == end) {
          // The join is the label just past this render range, so leaving to
          // it is the range's normal completion. Nested if/else chains whose
          // arms share the enclosing range's end label rely on this.
          joinBeyondRange = true;
        } else {
          if (!context.isInnermostSwitchJoin(labelKey(jumpTarget))) return null;
          joinBeyondRange = true;
        }
      }
      thenJumpIndex = thenTailIndex;
      joinIndex = target;
    } else if ("RETURN".equals(thenTail.kind()) || "THROW".equals(thenTail.kind())) {
      // The then block leaves the method, so the else path is the code that
      // follows the branch; no join instruction exists in that case.
      terminalThenIndex = thenTailIndex;
    } else {
      return null;
    }
    if (thenJumpIndex < 0 && terminalThenIndex < 0) return null;
    if (thenJumpIndex >= 0 && joinIndex == null) return null;

    int elseEnd = joinBeyondRange || terminalThenIndex >= 0 ? end : joinIndex;
    int lastExecutable = -1;
    for (int i = elseIndex + 1; i < elseEnd; i++) {
      if (!isBranchValueMetadata(operations.get(i))) lastExecutable = i;
    }
    if (lastExecutable < 0) return null;
    IrOperationModel elseTail = operations.get(lastExecutable);
    if (terminalThenIndex >= 0) {
      // Without a join instruction the else path simply continues; a forward
      // jump in its tail would mean the range continues past the branch.
      if ("JUMP".equals(elseTail.kind()) && !elseTail.operands().isEmpty()) {
        Integer target = labels.get(labelKey(elseTail.operands().get(0)));
        if (target == null || target > elseIndex) return null;
      }
    } else if (!joinBeyondRange && "JUMP".equals(elseTail.kind())
        && !elseTail.operands().isEmpty()
        && labelKey(elseTail.operands().get(0))
            .equals(labelKey(operations.get(joinIndex).operands().get(0)))) {
      elseEnd = lastExecutable;
    }

    // Fold the chain backwards in evaluation order. The fall-through path after
    // the chain reaches the then block, so the guard starts as always-taken;
    // each condition then extends it: a condition selecting the then block
    // short-circuits it, while one selecting the else block guards the rest.
    String guard = "true";
    for (int k = chainConditions.size() - 1; k >= 0; k--) {
      String condition = normalizeCondition(chainConditions.get(k));
      if (Boolean.TRUE.equals(chainTargetsThen.get(k))) {
        guard = "true".equals(guard) ? "true" : "(" + condition + " || " + guard + ")";
      } else {
        guard = "true".equals(guard)
            ? negateCondition(condition)
            : "(" + negateCondition(condition) + " && " + guard + ")";
      }
    }
    if ("true".equals(guard)) return null;

    // A TRY_BEGIN marker is emitted before the label whose block it protects.
    // Both arms have to start at the marker, otherwise the try/catch inside the
    // arm is rendered as plain code and its handler becomes unreachable.
    int thenStart = inlinedBlockStart(operations, thenIndex, labels);
    if (thenStart == thenIndex) thenStart++;
    int elseStart = inlinedBlockStart(operations, elseIndex, labels);
    if (elseStart == elseIndex) elseStart++;
    int thenEnd = terminalThenIndex >= 0 ? terminalThenIndex + 1 : thenJumpIndex;
    RenderResult thenBody = renderRange(
        operations, thenStart, thenEnd, indent + 1, context, labels);
    RenderResult elseBody = renderRange(
        operations, elseStart, elseEnd, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (").append(guard).append(") {\n");
    source.append(thenBody.source());
    source.append(indent(indent)).append("} else {\n");
    source.append(elseBody.source());
    source.append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(),
        thenBody.complete() && elseBody.complete(),
        terminalThenIndex >= 0 || joinBeyondRange ? end - 1 : joinIndex - 1);
  }

  /**
   * Flattens a short-circuit condition chain that guards a loop edge. javac
   * shares suffixes of {@code a && b} / {@code a || b} by branching into the
   * middle of the chain; the chain ends with a jump to the loop header
   * (continue) or to the loop's exit label (break). The condition under which
   * the edge is skipped is rebuilt as one expression so nested guards keep
   * their short-circuit order instead of collapsing into empty ifs.
   */
  private static ConditionalRender renderLoopEdgeGuard(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    int chainEnd = -1;
    String action = null;
    for (int i = branchIndex; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        if (context.isInnermostLoop(labelKey(target))) {
          action = context.hasLoopLabel(labelKey(target))
              ? "continue " + jsLabel(target) : "continue";
        } else if (context.isActiveLoop(labelKey(target))
            && context.hasLoopLabel(labelKey(target))) {
          action = "continue " + jsLabel(target);
        } else if (context.isInnermostBreakTarget(labelKey(target))) {
          action = "break";
        }
        if (action == null) return null;
        chainEnd = i;
        break;
      }
      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        continue;
      }
      if ("LABEL".equals(operation.kind()) || isExpressionOnly(operation)) continue;
      return null;
    }
    if (chainEnd < 0 || chainEnd == branchIndex) return null;

    // Every branch of the chain either re-enters the chain or skips it to the
    // instruction right after the guarded jump. Any other forward destination
    // means the chain is not a plain guard.
    for (int i = branchIndex; i < chainEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(operation.kind())
          || operation.operands().size() < 2) continue;
      Integer target = labels.get(labelKey(operation.operands().get(1)));
      if (target == null || target <= i) return null;
      if (target > chainEnd && target != chainEnd + 1) return null;
    }

    // An internal label that another part of the method still jumps to would
    // make this flattening hide a live edge.
    for (int i = branchIndex; i <= chainEnd; i++) {
      if (!"LABEL".equals(operations.get(i).kind())
          || operations.get(i).operands().isEmpty()) continue;
      String internal = labelKey(operations.get(i).operands().get(0));
      for (int j = 0; j < operations.size(); j++) {
        if (j >= branchIndex && j <= chainEnd) continue;
        IrOperationModel other = operations.get(j);
        String target = null;
        if ("JUMP".equals(other.kind()) && !other.operands().isEmpty()) {
          target = other.operands().get(0);
        } else if ("CONDITIONAL_BRANCH".equals(other.kind())
            && other.operands().size() >= 2) {
          target = other.operands().get(1);
        }
        if (target != null && internal.equals(labelKey(target))) return null;
      }
    }

    Map<Integer, String> memo = new java.util.HashMap<>();
    String reachesJoin = guardReachCondition(
        operations, branchIndex, chainEnd, labels, memo);
    if (reachesJoin == null || "false".equals(reachesJoin)) return null;
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (!(").append(reachesJoin).append(")) ")
        .append(action).append(";\n");
    return new ConditionalRender(source.toString(), true, chainEnd);
  }

  /**
   * Builds the expression that is true when the chain starting at the index
   * skips the guarded jump. Jumps into the middle of the chain are followed so
   * short-circuit suffixes keep their evaluation order.
   */
  private static String guardReachCondition(
      List<IrOperationModel> operations,
      int index,
      int chainEnd,
      Map<String, Integer> labels,
      Map<Integer, String> memo) {
    if (index >= chainEnd) return "false";
    String cached = memo.get(index);
    if (cached != null) return cached;
    String result;
    IrOperationModel operation = operations.get(index);
    if ("LABEL".equals(operation.kind()) || isExpressionOnly(operation)) {
      result = guardReachCondition(operations, index + 1, chainEnd, labels, memo);
    } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
        && operation.operands().size() >= 2) {
      Integer target = labels.get(labelKey(operation.operands().get(1)));
      if (target == null || target <= index) return null;
      String taken = target > chainEnd
          ? "true"
          : guardReachCondition(operations, target, chainEnd, labels, memo);
      String fallthrough = guardReachCondition(operations, index + 1, chainEnd, labels, memo);
      if (taken == null || fallthrough == null) return null;
      String condition = normalizeCondition(operation.operands().get(0));
      if ("true".equals(taken) && "false".equals(fallthrough)) {
        result = "(" + condition + ")";
      } else if ("false".equals(fallthrough)) {
        result = "(" + condition + " && " + taken + ")";
      } else if ("true".equals(taken)) {
        result = "(" + condition + " || " + fallthrough + ")";
      } else if ("false".equals(taken)) {
        result = "(!(" + condition + ") && " + fallthrough + ")";
      } else {
        result = "((" + condition + ") ? " + taken + " : " + fallthrough + ")";
      }
    } else {
      return null;
    }
    memo.put(index, result);
    return result;
  }

  private static ConditionalRender renderBranchToReturn(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    if (branch.operands().size() < 2) return null;
    Integer targetIndex = labels.get(labelKey(branch.operands().get(1)));
    if (targetIndex == null || targetIndex <= branchIndex) return null;
    // A terminal block can live at or beyond the end of the current render
    // range, for example another case body of an enclosing switch. Its return
    // is still a faithful rendering of the taken edge, so inline it and keep
    // the fall-through path owned by this range.
    boolean targetBeyondRange = targetIndex >= end;
    // A try-region start is not an ordinary early-return destination: keep its
    // markers available so renderTryCatch can preserve the protected extent.
    if (findTryBeginBeforeTarget(
        operations, branchIndex + 1, Math.min(targetIndex, end), branch.operands().get(1),
        labels) >= 0) {
      return null;
    }

    int returnIndex = -1;
    int returnScanEnd = targetBeyondRange ? operations.size() : end;
    for (int i = targetIndex + 1; i < returnScanEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if (isBranchValueMetadata(operation)) continue;
      if ("RETURN".equals(operation.kind())) {
        returnIndex = i;
        break;
      }
      if (!isExpressionOnly(operation) || "DROP".equals(operation.kind())
          || "STACK_OPERATION".equals(operation.kind()) || "DUP".equals(operation.kind())
          || "SWAP".equals(operation.kind())
          || operation.operands().stream().anyMatch(value ->
              value.contains("<unknown>") || value.contains("<exception>"))) return null;
    }
    if (returnIndex < 0) return null;
    IrOperationModel returnOperation = operations.get(returnIndex);
    if (returnOperation.operands().stream().anyMatch(value ->
        value.contains("<unknown>") || value.contains("<exception>"))) return null;

    int fallthroughEnd = Math.min(targetIndex, end);
    int skipReturnJump = findForwardJoinJump(
        operations, branchIndex + 1, targetIndex, end, labels);
    if (skipReturnJump >= 0 && onlyMetadataBetween(
        operations, skipReturnJump + 1, targetIndex)) {
      String skippedReturnJoin = operations.get(skipReturnJump).operands().get(0);
      Integer skippedReturnJoinIndex = labels.get(labelKey(skippedReturnJoin));
      if (skippedReturnJoinIndex != null && skippedReturnJoinIndex > returnIndex) {
        fallthroughEnd = skipReturnJump;
      } else {
        skipReturnJump = -1;
      }
    } else {
      skipReturnJump = -1;
    }
    String returnStatement = returnOperation.operands().isEmpty()
        ? "return;\n"
        : "return " + normalizeExpression(returnOperation.operands().get(0)) + ";\n";

    if (targetBeyondRange) {
      RenderResult fallthrough = renderRange(
          operations, branchIndex + 1, fallthroughEnd, indent, context, labels);
      StringBuilder source = new StringBuilder();
      source.append(indent(indent)).append("if (")
          .append(normalizeCondition(branch.operands().get(0))).append(") {\n");
      source.append(indent(indent + 1)).append(returnStatement);
      source.append(indent(indent)).append("}\n").append(fallthrough.source());
      return new ConditionalRender(
          source.toString(), fallthrough.complete(), fallthroughEnd - 1);
    }

    // The shared return block can also be reached through the fall-through
    // path. When the fall-through range ends in a branch whose taken edge
    // leaves the block, render the shared return under the inverse condition.
    // When it ends in an ordinary statement, the fall-through path completes
    // by entering the block, so append the shared return. Skipping either case
    // would silently drop a path that never executes its own return.
    int trailingBranchIndex = -1;
    String trailingCondition = null;
    boolean completesIntoTarget = false;
    if (skipReturnJump < 0 && fallthroughEnd == targetIndex) {
      int lastExecutable = -1;
      for (int i = branchIndex + 1; i < fallthroughEnd; i++) {
        if (!isBranchValueMetadata(operations.get(i))) lastExecutable = i;
      }
      if (lastExecutable >= 0) {
        IrOperationModel tail = operations.get(lastExecutable);
        if ("CONDITIONAL_BRANCH".equals(tail.kind()) && tail.operands().size() >= 2) {
          Integer tailTarget = labels.get(labelKey(tail.operands().get(1)));
          if (tailTarget != null && tailTarget > returnIndex) {
            trailingBranchIndex = lastExecutable;
            trailingCondition = negateCondition(tail.operands().get(0));
          }
        } else if (!"RETURN".equals(tail.kind()) && !"THROW".equals(tail.kind())
            && !"JUMP".equals(tail.kind())) {
          completesIntoTarget = true;
        }
      }
      if (skipReturnJump < 0 && trailingBranchIndex < 0 && !completesIntoTarget) {
        // Nested structures can leave the fall-through range through an exit
        // edge instead of reaching its end (their renderers draw the guarded
        // path only). When every out-of-range control transfer in the range
        // points at the shared return block, control that leaves the range
        // executes it, so append the shared return.
        boolean exitsIntoTarget = false;
        boolean exitsElsewhere = false;
        for (int i = branchIndex + 1; i < fallthroughEnd; i++) {
          for (String referenced : referencedLabels(operations.get(i))) {
            Integer referencedIndex = labels.get(labelKey(referenced));
            if (referencedIndex == null || referencedIndex < fallthroughEnd) continue;
            if (referencedIndex.equals(targetIndex)) exitsIntoTarget = true;
            else exitsElsewhere = true;
          }
        }
        if (exitsIntoTarget && !exitsElsewhere) completesIntoTarget = true;
      }
    }

    RenderResult fallthrough = renderRange(
        operations, branchIndex + 1,
        trailingBranchIndex >= 0 ? trailingBranchIndex : fallthroughEnd,
        indent, context, labels);
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (")
        .append(normalizeCondition(branch.operands().get(0))).append(") {\n");
    source.append(indent(indent + 1)).append(returnStatement);
    source.append(indent(indent)).append("}\n").append(fallthrough.source());
    if (trailingCondition != null) {
      source.append(indent(indent)).append("if (").append(trailingCondition).append(") {\n")
          .append(indent(indent + 1)).append(returnStatement)
          .append(indent(indent)).append("}\n");
    } else if (completesIntoTarget) {
      source.append(indent(indent)).append(returnStatement);
    }
    return new ConditionalRender(source.toString(), fallthrough.complete(), returnIndex);
  }

  private static ConditionalRender renderTernaryValueMerge(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    if (branch.operands().size() < 2) return null;
    String condition = branch.operands().get(0);
    String target = branch.operands().get(1);
    Integer targetIndex = labels.get(labelKey(target));
    if (targetIndex == null || targetIndex <= branchIndex || targetIndex >= end) return null;

    int jumpIndex = findForwardJoinJump(
        operations, branchIndex + 1, targetIndex, end, labels);
    if (jumpIndex < 0 || jumpIndex >= targetIndex) return null;
    IrOperationModel jump = operations.get(jumpIndex);
    if (jump.operands().isEmpty()) return null;
    Integer joinIndex = labels.get(labelKey(jump.operands().get(0)));
    if (joinIndex == null || joinIndex <= targetIndex || joinIndex >= end) return null;

    String fallthroughValue = lastExpressionFromRange(
        operations, branchIndex + 1, jumpIndex);
    String targetValue = lastExpressionFromRange(
        operations, targetIndex + 1, joinIndex);
    if (fallthroughValue == null || targetValue == null) return null;

    int sinkIndex = findValueMergeSink(operations, joinIndex + 1, end);
    if (sinkIndex < 0) return null;
    IrOperationModel sink = operations.get(sinkIndex);
    long unknownCount = sink.operands().stream()
        .mapToLong(value -> occurrences(value, "<unknown>")).sum();
    if (unknownCount != 1) return null;

    String ternary = "(" + normalizeCondition(condition) + " ? "
        + targetValue + " : " + fallthroughValue + ")";
    StringBuilder source = new StringBuilder();
    if ("LOCAL_WRITE".equals(sink.kind()) && sink.operands().size() >= 2
        && sink.operands().get(1).contains("<unknown>")) {
      // The merged value can be nested inside a larger expression, such as a
      // constructor call whose last argument is the conditional result.
      // Substitute the ternary and lower the write normally so object
      // creation goes through the same runtime path as any other write.
      java.util.List<String> replacedOperands =
          new java.util.ArrayList<>(sink.operands());
      replacedOperands.set(1, replacedOperands.get(1).replace("<unknown>", ternary));
      IrOperationModel replaced = new IrOperationModel(
          sink.kind(), replacedOperands, sink.sourceInstruction());
      String lowered = translateOperation(replaced, context);
      if (lowered == null || lowered.isBlank()) return null;
      source.append(indent(indent)).append(lowered).append("\n");
      return new ConditionalRender(source.toString(), true, sinkIndex);
    }
    if ("CONDITIONAL_BRANCH".equals(sink.kind()) && !sink.operands().isEmpty()) {
      String replacedCondition = sink.operands().get(0).replace("<unknown>", ternary);
      return renderConditional(
          operations, sinkIndex, end, indent, context, labels, replacedCondition);
    }
    if (!List.of("FIELD_WRITE", "METHOD_CALL", "CALL_VALUE", "RETURN")
        .contains(sink.kind())) return null;
    List<String> replacedOperands = sink.operands().stream()
        .map(value -> value.replace("<unknown>", ternary)).toList();
    IrOperationModel replaced = new IrOperationModel(
        sink.kind(), replacedOperands, sink.sourceInstruction());
    String lowered = translateOperation(replaced, context);
    if (lowered == null || lowered.isBlank()) return null;
    source.append(indent(indent)).append(lowered).append("\n");
    return new ConditionalRender(source.toString(), true, sinkIndex);
  }

  /**
   * Structures a short-circuit chain: several conditionals whose taken edges
   * lead to two expression-valued blocks that merge into one sink. This is the
   * JVM shape for {@code return a || b || c;} and similar boolean materialized
   * returns, where javac emits constants in two blocks and a shared exit. Each
   * condition becomes a guard for its block value; the block reached by
   * fall-through supplies the remaining value.
   */
  private static ConditionalRender renderConstantMergeChain(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel firstBranch = operations.get(branchIndex);
    if (firstBranch.operands().size() < 2) return null;
    Integer firstTarget = labels.get(labelKey(firstBranch.operands().get(1)));
    if (firstTarget == null || firstTarget <= branchIndex || firstTarget >= end) return null;

    List<Integer> branchIndices = new java.util.ArrayList<>();
    List<Integer> branchTargets = new java.util.ArrayList<>();
    int secondTarget = -1;
    int limit = firstTarget;
    for (int i = branchIndex; i < limit; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (operation.operands().size() < 2) return null;
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null || target <= i || target >= end) return null;
        if (secondTarget < 0) {
          if (target != firstTarget) {
            secondTarget = target;
            if (secondTarget < limit) limit = secondTarget;
          }
        } else if (target != firstTarget && target != secondTarget) {
          return null;
        }
        branchIndices.add(i);
        branchTargets.add(target);
      } else if (isBranchValueMetadata(operation) || isExpressionOnly(operation)) {
        continue;
      } else {
        return null;
      }
    }
    if (secondTarget < 0 || branchIndices.isEmpty()) return null;

    int earlier = Math.min(firstTarget, secondTarget);
    int later = Math.max(firstTarget, secondTarget);
    String earlierValue = constantBlockValue(operations, earlier, end);
    String laterValue = constantBlockValue(operations, later, end);
    if (earlierValue == null || laterValue == null) return null;
    Integer earlierJoin = constantBlockJoin(operations, earlier, end, labels);
    Integer laterJoin = constantBlockJoin(operations, later, end, labels);
    if (earlierJoin == null || laterJoin == null || !earlierJoin.equals(laterJoin)) return null;
    int joinIndex = earlierJoin;
    if (joinIndex <= later || joinIndex >= end) return null;

    int sinkIndex = findValueMergeSink(operations, joinIndex + 1, end);
    if (sinkIndex < 0) return null;
    IrOperationModel sink = operations.get(sinkIndex);
    long unknownCount = sink.operands().stream()
        .mapToLong(value -> occurrences(value, "<unknown>")).sum();
    if (unknownCount != 1) return null;

    if ("RETURN".equals(sink.kind())) {
      StringBuilder source = new StringBuilder();
      boolean complete = true;
      int previous = branchIndex;
      for (int k = 0; k < branchIndices.size(); k++) {
        int current = branchIndices.get(k);
        RenderResult prefix = renderRange(
            operations, previous + (k == 0 ? 0 : 1), current, indent, context, labels);
        source.append(prefix.source());
        complete &= prefix.complete();
        source.append(indent(indent)).append("if (")
            .append(normalizeCondition(operations.get(current).operands().get(0)))
            .append(") return ")
            .append(branchTargets.get(k) == earlier ? earlierValue : laterValue)
            .append(";\n");
        previous = current;
      }
      RenderResult tail = renderRange(operations, previous + 1, earlier, indent, context, labels);
      source.append(tail.source());
      complete &= tail.complete();
      source.append(indent(indent)).append("return ").append(earlierValue).append(";\n");
      return new ConditionalRender(source.toString(), complete, sinkIndex);
    }

    for (int i = branchIndex; i < earlier; i++) {
      if ("DROP".equals(operations.get(i).kind())) return null;
    }
    String folded = earlierValue;
    for (int k = branchIndices.size() - 1; k >= 0; k--) {
      folded = "(" + normalizeCondition(operations.get(branchIndices.get(k)).operands().get(0))
          + " ? " + (branchTargets.get(k) == earlier ? earlierValue : laterValue)
          + " : " + folded + ")";
    }
    StringBuilder source = new StringBuilder();
    if ("LOCAL_WRITE".equals(sink.kind()) && sink.operands().size() >= 2
        && sink.operands().get(1).contains("<unknown>")) {
      // A constructor or other compound expression can hold the merged value.
      java.util.List<String> replacedOperands =
          new java.util.ArrayList<>(sink.operands());
      replacedOperands.set(1, replacedOperands.get(1).replace("<unknown>", folded));
      IrOperationModel replaced = new IrOperationModel(
          sink.kind(), replacedOperands, sink.sourceInstruction());
      String lowered = translateOperation(replaced, context);
      if (lowered == null || lowered.isBlank()) return null;
      source.append(indent(indent)).append(lowered).append("\n");
      return new ConditionalRender(source.toString(), true, sinkIndex);
    }
    if (!List.of("FIELD_WRITE", "METHOD_CALL", "RETURN").contains(sink.kind())) return null;
    final String foldedValue = folded;
    List<String> replacedOperands = sink.operands().stream()
        .map(value -> value.replace("<unknown>", foldedValue)).toList();
    IrOperationModel replaced = new IrOperationModel(
        sink.kind(), replacedOperands, sink.sourceInstruction());
    String lowered = translateOperation(replaced, context);
    if (lowered == null || lowered.isBlank()) return null;
    source.append(indent(indent)).append(lowered).append("\n");
    return new ConditionalRender(source.toString(), true, sinkIndex);
  }

  /**
   * Rebuilds a decision tree that materializes one value per path. javac lowers
   * short-circuit boolean expressions with intermediate labels, so a leaf block
   * can be reached both as a branch target and by falling through the previous
   * block. Nested ternaries keep the evaluation order of every path, and the
   * shared sink becomes a single return or assignment instead of an unresolved
   * jump to an unknown value.
   */
  private record DecisionTreeResult(
      String expression, int sinkLabel, Set<Integer> owned) {}

  private static DecisionTreeResult computeDecisionTree(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      Map<String, Integer> labels) {
    if (!"CONDITIONAL_BRANCH".equals(operations.get(branchIndex).kind())) return null;
    int blockEnd = nextLabelIndex(operations, branchIndex + 1, end);
    Set<Integer> owned = new HashSet<>();
    Set<Integer> active = new HashSet<>();
    Map<Integer, String> memo = new HashMap<>();
    int[] sink = {-1};
    String expression = decisionTreeValue(
        operations, branchIndex, blockEnd, end, labels, owned, active, memo, sink, 0);
    if (expression == null || sink[0] < 0 || sink[0] >= end) return null;
    if (!"LABEL".equals(operations.get(sink[0]).kind())) return null;

    // The tree owns every block it evaluated. A branch into one of those labels
    // from outside the consumed range would mean the blocks are shared, and
    // consuming them here would hide that path.
    owned.add(sink[0]);
    int sinkIndex = nextExecutableIndex(operations, sink[0] + 1, end);
    for (int i = 0; i < operations.size(); i++) {
      if (i >= branchIndex && i <= sinkIndex) continue;
      IrOperationModel operation = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(operation.kind())
          && !"JUMP".equals(operation.kind())) continue;
      if (operation.operands().isEmpty()) continue;
      Integer targetIndex = labels.get(
          labelKey(operation.operands().get(operation.operands().size() - 1)));
      if (targetIndex != null && owned.contains(targetIndex)) return null;
    }
    return new DecisionTreeResult(expression, sink[0], owned);
  }

  private static ConditionalRender renderDecisionTreeValue(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    DecisionTreeResult tree = computeDecisionTree(operations, branchIndex, end, labels);
    if (tree == null) return null;
    int sinkIndex = nextExecutableIndex(operations, tree.sinkLabel() + 1, end);
    if (sinkIndex < 0 || sinkIndex >= end) return null;
    IrOperationModel sinkOperation = operations.get(sinkIndex);
    boolean returnsValue = "RETURN".equals(sinkOperation.kind());
    boolean assignsValue = "LOCAL_WRITE".equals(sinkOperation.kind())
        && sinkOperation.operands().size() >= 2;
    // A boolean decision tree frequently feeds a field write, such as a
    // widget's enabled flag. The value is still a pure expression, so the
    // same nested ternary can be substituted for the stack phi.
    boolean writesValue = "FIELD_WRITE".equals(sinkOperation.kind())
        && sinkOperation.operands().size() >= 3;
    if ((!returnsValue && !assignsValue && !writesValue)
        || !"<unknown>".equals(
            sinkOperation.operands().get(sinkOperation.operands().size() - 1))) {
      // The merged value can sit below more expression operands and be
      // consumed later, for example two boolean arguments of one call. Keep
      // each merged value in a local so later substitutions preserve order.
      return renderStackValueMerge(
          operations, tree, end, indent, context, labels);
    }

    StringBuilder source = new StringBuilder();
    String expression = tree.expression();
    if (returnsValue) {
      source.append(indent(indent)).append("return ").append(expression).append(";\n");
    } else if (assignsValue) {
      String local = sinkOperation.operands().get(0);
      String assignment = context.declareLocal(local) ? "var " + local : local;
      source.append(indent(indent)).append(assignment).append(" = ")
          .append(expression).append(";\n");
    } else {
      List<String> operands = new ArrayList<>(sinkOperation.operands());
      operands.set(operands.size() - 1, expression);
      String lowered = translateOperation(new IrOperationModel(
          sinkOperation.kind(), operands, sinkOperation.sourceInstruction()), context);
      if (lowered == null || lowered.isBlank()) return null;
      source.append(indent(indent)).append(lowered).append("\n");
    }
    return new ConditionalRender(source.toString(), true, sinkIndex);
  }

  /**
   * Materializes one or more acyclic decision-tree values that are consumed
   * later, such as the two boolean arguments of a single call. Each merged
   * value becomes a local at its merge point; the consumer receives those
   * locals in stack order. Intermediate expression-only reads are folded into
   * the existing consumer operands, so anything with an observable side effect
   * is rejected rather than reordered.
   */
  private static ConditionalRender renderStackValueMerge(
      List<IrOperationModel> operations,
      DecisionTreeResult first,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    StringBuilder source = new StringBuilder();
    List<String> values = new ArrayList<>();
    String firstVariable = context.newMergeVariable();
    values.add(firstVariable);
    source.append(indent(indent)).append("var ").append(firstVariable)
        .append(" = ").append(first.expression()).append(";\n");

    int index = first.sinkLabel() + 1;
    while (index < end) {
      IrOperationModel operation = operations.get(index);
      if ("LABEL".equals(operation.kind())) {
        index++;
        continue;
      }
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        DecisionTreeResult tree = computeDecisionTree(operations, index, end, labels);
        if (tree == null) return null;
        String variable = context.newMergeVariable();
        values.add(variable);
        source.append(indent(indent)).append("var ").append(variable)
            .append(" = ").append(tree.expression()).append(";\n");
        index = tree.sinkLabel() + 1;
        continue;
      }
      long unknownCount = operation.operands().stream()
          .mapToLong(value -> occurrences(value, "<unknown>")).sum();
      if (unknownCount > 0) {
        if (unknownCount != values.size()) return null;
        List<String> substituted = substituteUnknowns(operation.operands(), values);
        if (substituted == null) return null;
        String lowered = translateOperation(new IrOperationModel(
            operation.kind(), substituted, operation.sourceInstruction()), context);
        if (lowered == null || lowered.isBlank()) return null;
        source.append(indent(indent)).append(lowered).append("\n");
        return new ConditionalRender(source.toString(), true, index);
      }
      if (!isPureStackValue(operation)) return null;
      index++;
    }
    return null;
  }

  private static boolean isPureStackValue(IrOperationModel operation) {
    return switch (operation.kind()) {
      case "CONSTANT", "LOCAL_READ", "FIELD_READ", "ARRAY_READ", "ARRAY_LENGTH",
          "ARITHMETIC", "TYPE_CONVERSION", "TYPE_CHECK" -> true;
      default -> false;
    };
  }

  private static List<String> substituteUnknowns(
      List<String> operands, List<String> values) {
    List<String> result = new ArrayList<>();
    int next = 0;
    for (String operand : operands) {
      String value = operand;
      int marker;
      while (next < values.size() && (marker = value.indexOf("<unknown>")) >= 0) {
        value = value.substring(0, marker) + values.get(next)
            + value.substring(marker + "<unknown>".length());
        next++;
      }
      result.add(value);
    }
    return next == values.size() ? result : null;
  }

  /**
   * Conservative check for expressions with observable evaluation effects.
   * Known runtime helpers and arithmetic are pure; any other invocation keeps
   * its evaluation with the comma operator instead of being dropped.
   */
  private static boolean conditionMayHaveSideEffects(String condition) {
    for (int i = 0; i < condition.length(); i++) {
      if (condition.charAt(i) != '(') continue;
      int end = i;
      int start = i;
      while (start > 0) {
        char c = condition.charAt(start - 1);
        if (Character.isJavaIdentifierPart(c) || c == '.' || c == '$') start--;
        else break;
      }
      String callee = condition.substring(start, end);
      if (callee.isEmpty()) continue;
      String simple = callee.substring(callee.lastIndexOf('.') + 1);
      if (callee.startsWith("GoldClientRuntime.")
          || callee.startsWith("Math.")
          || callee.startsWith("new ")
          || simple.equals("instanceOf")
          || simple.equals("checkCast")
          || simple.equals("arrayGet")
          || simple.equals("arraySet")
          || simple.equals("arrayLength")
          || simple.equals("newArray")
          || simple.equals("newMultiArray")
          || simple.equals("toInt32")
          || simple.equals("toNumber")
          || simple.equals("fround")) {
        continue;
      }
      return true;
    }
    return false;
  }

  private static String decisionTreeValue(
      List<IrOperationModel> operations,
      int blockStart,
      int blockEnd,
      int end,
      Map<String, Integer> labels,
      java.util.Set<Integer> owned,
      java.util.Set<Integer> active,
      java.util.Map<Integer, String> memo,
      int[] sink,
      int depth) {
    if (depth > 24) { return null; }
    List<String> conditions = new java.util.ArrayList<>();
    List<Integer> targets = new java.util.ArrayList<>();
    int lastValueIndex = -1;
    for (int i = blockStart; i < blockEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind())) { return null; }
      if ("TRY_BEGIN".equals(operation.kind()) || "TRY_END".equals(operation.kind())
          || "CATCH_BEGIN".equals(operation.kind())) { return null; }
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (operation.operands().size() < 2) { return null; }
        String condition = operation.operands().get(0);
        if (condition.contains("<unknown>") || condition.contains("<exception>")) { return null; }
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null || target <= blockStart || target >= end) { return null; }
        conditions.add(condition);
        targets.add(target);
        continue;
      }
      if ("JUMP".equals(operation.kind())) {
        // A jump only ends a pure value block; condition blocks fall through to
        // the next label so every path stays inside the tree.
        if (!conditions.isEmpty() || lastValueIndex < 0
            || operation.operands().isEmpty()) { return null; }
        Integer target = labels.get(labelKey(operation.operands().get(0)));
        if (target == null || target <= blockStart || target >= end) { return null; }
        String value = expressionFromOperation(operations.get(lastValueIndex));
        if (value == null) { return null; }
        if (sink[0] < 0) sink[0] = target;
        else if (sink[0] != target) { return null; }
        return value;
      }
      if ("RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())) { return null; }
      if (!isExpressionOnly(operation) || "DROP".equals(operation.kind())
          || "DUP".equals(operation.kind()) || "SWAP".equals(operation.kind())
          || "STACK_OPERATION".equals(operation.kind())) { return null; }
      String value = expressionFromOperation(operation);
      if (value == null) { return null; }
      if (value.contains("<unknown>") || value.contains("<exception>")) { return null; }
      lastValueIndex = i;
    }
    if (conditions.isEmpty()) {
      // A leaf without a jump falls straight into the sink label.
      if (lastValueIndex < 0) { return null; }
      String value = expressionFromOperation(operations.get(lastValueIndex));
      if (value == null) { return null; }
      if (sink[0] < 0) sink[0] = blockEnd;
      else if (sink[0] != blockEnd) { return null; }
      return value;
    }
    String fallthrough = decisionTreeValueAt(
        operations, blockEnd, end, labels, owned, active, memo, sink, depth + 1);
    if (fallthrough == null) { return null; }
    boolean effectFree = true;
    for (String condition : conditions) {
      if (conditionMayHaveSideEffects(normalizeCondition(condition))) {
        effectFree = false;
        break;
      }
    }

    String expression;
    if (effectFree) {
      // A branch to the fall-through label skips the remaining conditions and
      // takes the block's eventual value. Build guards in condition order so
      // the shared fall-through value appears once instead of once per skip.
      expression = fallthrough;
      for (int k = conditions.size() - 1; k >= 0; k--) {
        if (targets.get(k) == blockEnd) continue;
        String targetValue = decisionTreeValueAt(
            operations, targets.get(k), end, labels, owned, active, memo, sink, depth + 1);
        if (targetValue == null) return null;
        StringBuilder guard = new StringBuilder();
        for (int j = 0; j <= k; j++) {
          String condition = normalizeCondition(conditions.get(j));
          if (guard.length() > 0) guard.append(" && ");
          guard.append(j == k ? "(" + condition + ")" : "!(" + condition + ")");
        }
        expression = "(" + guard + " ? " + targetValue + " : " + expression + ")";
        if (expression.length() > 8192) return null;
      }
    } else {
      expression = fallthrough;
      for (int k = conditions.size() - 1; k >= 0; k--) {
        String condition = normalizeCondition(conditions.get(k));
        String targetValue = targets.get(k) == blockEnd
            ? fallthrough
            : decisionTreeValueAt(
                operations, targets.get(k), end, labels, owned, active, memo, sink, depth + 1);
        if (targetValue == null) return null;
        expression = "(" + condition + " ? " + targetValue
            + " : " + expression + ")";
        if (expression.length() > 8192) return null;
      }
    }
    return expression;
  }

  private static String decisionTreeValueAt(
      List<IrOperationModel> operations,
      int labelIndex,
      int end,
      Map<String, Integer> labels,
      java.util.Set<Integer> owned,
      java.util.Set<Integer> active,
      java.util.Map<Integer, String> memo,
      int[] sink,
      int depth) {
    if (labelIndex < 0 || labelIndex >= end) return null;
    if (!"LABEL".equals(operations.get(labelIndex).kind())) return null;
    String cached = memo.get(labelIndex);
    if (cached != null) return cached;
    if (!active.add(labelIndex)) return null;
    owned.add(labelIndex);
    int blockEnd = nextLabelIndex(operations, labelIndex + 1, end);
    String value = decisionTreeValue(
        operations, labelIndex + 1, blockEnd, end, labels, owned, active, memo, sink, depth);
    active.remove(labelIndex);
    if (value != null) memo.put(labelIndex, value);
    return value;
  }

  private static int nextLabelIndex(
      List<IrOperationModel> operations, int start, int end) {
    for (int i = start; i < end; i++) {
      if ("LABEL".equals(operations.get(i).kind())) return i;
    }
    return end;
  }

  private static String constantBlockValue(
      List<IrOperationModel> operations, int labelIndex, int end) {
    int valueIndex = nextExecutableIndex(operations, labelIndex + 1, end);
    if (valueIndex < 0) return null;
    IrOperationModel value = operations.get(valueIndex);
    if (!isExpressionOnly(value) || "DROP".equals(value.kind())
        || "DUP".equals(value.kind()) || "SWAP".equals(value.kind())
        || "STACK_OPERATION".equals(value.kind())) return null;
    return expressionFromOperation(value);
  }

  private static Integer constantBlockJoin(
      List<IrOperationModel> operations, int labelIndex, int end, Map<String, Integer> labels) {
    int valueIndex = nextExecutableIndex(operations, labelIndex + 1, end);
    if (valueIndex < 0) return null;
    for (int i = valueIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        Integer target = labels.get(labelKey(operation.operands().get(0)));
        return target != null && target > labelIndex ? target : null;
      }
      if ("LABEL".equals(operation.kind())) return i;
      if (isExpressionOnly(operation)) continue;
      return null;
    }
    return null;
  }

  private static int findValueMergeSink(
      List<IrOperationModel> operations, int start, int end) {
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (isBranchValueMetadata(operation)) continue;
      if ("DROP".equals(operation.kind()) || "STACK_OPERATION".equals(operation.kind())
          || "DUP".equals(operation.kind()) || "SWAP".equals(operation.kind())) return -1;
      if (isExpressionOnly(operation)) continue;
      return i;
    }
    return -1;
  }

  private static int occurrences(String value, String token) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }

  private static String lastExpressionFromRange(
      List<IrOperationModel> operations, int start, int end) {
    String expression = null;
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (isBranchValueMetadata(operation)) continue;
      if (!isExpressionOnly(operation) || "DROP".equals(operation.kind())
          || "STACK_OPERATION".equals(operation.kind())
          || "SWAP".equals(operation.kind())) return null;
      // A constructor duplicate is stack plumbing for the value expression
      // that follows (OBJECT_CREATE, DUP, arguments, CALL_VALUE); it does not
      // contribute a value of its own.
      if ("DUP".equals(operation.kind())) continue;
      if (operation.operands().stream().anyMatch(value ->
          value.contains("<unknown>") || value.contains("<exception>"))) return null;
      String candidate = expressionFromOperation(operation);
      if (candidate != null) expression = candidate;
    }
    return expression;
  }

  private static String expressionFromOperation(IrOperationModel operation) {
    List<String> operands = operation.operands();
    if (operands.isEmpty()) return null;
    return switch (operation.kind()) {
      case "CONSTANT", "LOCAL_READ", "OBJECT_CREATE", "ARRAY_CREATE", "CALL_VALUE",
          "ARRAY_READ", "ARRAY_LENGTH", "ARITHMETIC", "TYPE_CONVERSION" -> operands.get(0);
      case "FIELD_READ", "TYPE_CHECK" -> operands.get(operands.size() - 1);
      default -> null;
    };
  }

  private static boolean isBranchValueMetadata(IrOperationModel operation) {
    return "LABEL".equals(operation.kind()) || "TRY_BEGIN".equals(operation.kind())
        || "TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind());
  }

  private static int findContinueJumpAtTarget(
      List<IrOperationModel> operations,
      int targetIndex,
      TranslationContext context) {
    for (int i = targetIndex + 1; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);
      if (isBranchValueMetadata(operation)) continue;
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) return -1;
      String loopTarget = labelKey(operation.operands().get(0));
      return context.isActiveLoop(loopTarget) ? i : -1;
    }
    return -1;
  }

  /**
   * Resolves the loop edge a jump ultimately performs. javac shares one back
   * edge between several jumps by routing them through a block whose only
   * content is another jump; following that chain keeps the edge visible as
   * {@code continue}/{@code break} instead of leaving it unresolved.
   */
  private static String loopEdgeAction(
      List<IrOperationModel> operations,
      String targetLabel,
      TranslationContext context,
      Map<String, Integer> labels) {
    String label = labelKey(targetLabel);
    for (int hop = 0; hop < 8; hop++) {
      if (context.isActiveLoop(label)) {
        return context.hasLoopLabel(label) ? "continue " + jsLabel(label) : "continue";
      }
      if (context.isBreakTarget(label)) {
        if (context.isInnermostBreakTarget(label)) return "break";
        String loopLabel = context.loopExitLabel(label);
        return loopLabel == null ? null : "break " + jsLabel(loopLabel);
      }
      Integer index = labels.get(label);
      if (index == null) return null;
      int jumpIndex = -1;
      for (int i = index + 1; i < operations.size(); i++) {
        IrOperationModel operation = operations.get(i);
        if (isBranchValueMetadata(operation)) continue;
        if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) jumpIndex = i;
        break;
      }
      if (jumpIndex < 0) return null;
      label = labelKey(operations.get(jumpIndex).operands().get(0));
    }
    return null;
  }

  /**
   * Returns the index of the return that ends a straight-line block starting
   * at {@code targetIndex}, or -1 when the block branches, throws, or carries
   * an unresolved stack/exception value. Such a block can be rendered wherever
   * a jump reaches it without changing the order of observable effects.
   */
  private static int straightLineReturnEnd(
      List<IrOperationModel> operations, int targetIndex) {
    if (targetIndex < 0 || targetIndex >= operations.size()) return -1;
    for (int i = targetIndex; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);
      if (operation.operands().stream().anyMatch(value ->
          value.contains("<unknown>") || value.contains("<exception>"))) return -1;
      switch (operation.kind()) {
        case "LABEL" -> {
          continue;
        }
        case "RETURN" -> {
          return i;
        }
        case "JUMP", "CONDITIONAL_BRANCH", "TABLE_SWITCH", "LOOKUP_SWITCH",
            "THROW", "TRY_BEGIN", "TRY_END", "CATCH_BEGIN" -> {
          return -1;
        }
        default -> {
        }
      }
    }
    return -1;
  }

  /**
   * Follows a chain of labels whose block is only another jump, and reports
   * whether it ends at a void return. Only a bare {@code return;} is emitted,
   * so a value-returning method is never truncated by this shortcut.
   */
  private static boolean jumpChainEndsInVoidReturn(
      List<IrOperationModel> operations,
      int targetIndex,
      Map<String, Integer> labels) {
    if (targetIndex < 0 || targetIndex >= operations.size()) return false;
    int index = targetIndex;
    for (int hop = 0; hop < 8; hop++) {
      int cursor = index + 1;
      while (cursor < operations.size()) {
        if ("LABEL".equals(operations.get(cursor).kind())) {
          cursor++;
          continue;
        }
        break;
      }
      if (cursor >= operations.size()) return false;
      IrOperationModel operation = operations.get(cursor);
      if ("RETURN".equals(operation.kind())) return operation.operands().isEmpty();
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) return false;
      Integer next = labels.get(labelKey(operation.operands().get(0)));
      if (next == null || next <= index) return false;
      index = next;
    }
    return false;
  }

  /**
   * Finds the exclusive end of a small shared loop-continuation tail starting
   * at {@code targetIndex}. The tail must execute only expressions and a
   * branch chain and finish by leaving the current range or jumping elsewhere
   * forward. Returning that destination as the limit lets the forward-arm
   * renderer duplicate the guard without swallowing unrelated code.
   */
  private static int forwardLoopTailLimit(
      List<IrOperationModel> operations,
      int targetIndex,
      TranslationContext context,
      Map<String, Integer> labels) {
    int limit = -1;
    boolean conditional = false;
    int stop = Math.min(operations.size(), targetIndex + 64);
    for (int i = targetIndex; i < stop; i++) {
      IrOperationModel operation = operations.get(i);
      switch (operation.kind()) {
        case "LABEL" -> {
        }
        case "CONDITIONAL_BRANCH" -> {
          if (operation.operands().size() < 2) return -1;
          Integer target = labels.get(labelKey(operation.operands().get(1)));
          if (target == null || target <= targetIndex) return -1;
          conditional = true;
        }
        case "JUMP" -> {
          if (operation.operands().isEmpty()) return -1;
          String target = labelKey(operation.operands().get(0));
          if (context.isActiveLoop(target)) continue;
          Integer targetIndex2 = labels.get(target);
          if (targetIndex2 == null || targetIndex2 <= targetIndex) return -1;
          limit = targetIndex2;
        }
        default -> {
          if (!isExpressionOnly(operation)) return -1;
        }
      }
      if (limit >= 0) return conditional ? limit : -1;
    }
    return -1;
  }

  private static int loopTailJumpIndex(
      List<IrOperationModel> operations,
      int targetIndex,
      TranslationContext context) {
    boolean seenStatement = false;
    for (int i = targetIndex + 1; i < operations.size() && i < targetIndex + 32; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind())) {
        if (seenStatement) return -1;
        continue;
      }
      if ("JUMP".equals(operation.kind())) {
        if (operation.operands().isEmpty()) return -1;
        return context.isActiveLoop(labelKey(operation.operands().get(0))) ? i : -1;
      }
      switch (operation.kind()) {
        case "CONDITIONAL_BRANCH", "RETURN", "THROW", "TABLE_SWITCH", "LOOKUP_SWITCH",
            "TRY_BEGIN", "TRY_END", "CATCH_BEGIN" -> {
          return -1;
        }
        default -> seenStatement = true;
      }
    }
    return -1;
  }

  /** The label a jump or conditional branch transfers control to. */
  private static String branchTarget(IrOperationModel operation) {
    if ("CONDITIONAL_BRANCH".equals(operation.kind()) && operation.operands().size() >= 2) {
      return operation.operands().get(1);
    }
    if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
      return operation.operands().get(0);
    }
    return null;
  }

  /**
   * Structures a condition chain that guards one shared block. javac lowers
   * {@code if (a || (b = ...)) { ...; continue; }} and similar short-circuit
   * guards by letting each condition either enter a block placed right before
   * the join or skip it. The block is rendered once per arm, which keeps every
   * path faithful even though a plain nested if/else cannot express the sharing
   * (a skip condition sends only its taken edge to the join, while its
   * fall-through still enters the block). The shared block may either leave the
   * enclosing loop or fall through to the join; a join at the exclusive range
   * end is treated the same way.
   */
  private static ConditionalRender renderGuardBlockChain(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel first = operations.get(branchIndex);
    if (first.operands().size() < 2) return null;
    Integer blockIndex = labels.get(labelKey(first.operands().get(1)));
    if (blockIndex == null || blockIndex <= branchIndex || blockIndex >= end) return null;

    List<Integer> conditionals = new java.util.ArrayList<>();
    List<Boolean> entersBlock = new java.util.ArrayList<>();
    Integer joinIndex = null;
    for (int i = branchIndex; i < blockIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind()) && operation.operands().size() >= 2) {
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null) return null;
        if (target.equals(blockIndex)) {
          entersBlock.add(true);
        } else if (target > blockIndex && target <= end) {
          // A target exactly at the exclusive range end is this range's join:
          // the arm falls out after the shared block and the enclosing renderer
          // continues there. If that join label starts a protected region, use
          // its TRY_BEGIN marker as the boundary so the range never splits the
          // marker from the label it annotates; the enclosing renderer then
          // structures the whole try/catch once.
          int join = inlinedBlockStart(operations, target, labels);
          if (joinIndex == null) joinIndex = join;
          else if (!joinIndex.equals(join)) return null;
          entersBlock.add(false);
        } else {
          return null;
        }
        conditionals.add(i);
        continue;
      }
      switch (operation.kind()) {
        case "JUMP", "RETURN", "THROW", "TABLE_SWITCH", "LOOKUP_SWITCH",
            "TRY_BEGIN", "TRY_END", "CATCH_BEGIN" -> {
          return null;
        }
        default -> {
        }
      }
    }
    if (conditionals.isEmpty() || joinIndex == null) return null;

    // The shared block needs a tail instruction, but it does not have to leave
    // the enclosing loop: a block that simply falls through to the join is the
    // common javac shape for `if (a || b) { block }`. Both duplicated arms end
    // at the join, so falling into it is the intended path.
    int blockTail = -1;
    for (int i = blockIndex; i < joinIndex; i++) {
      if (!isLoopExitMetadata(operations.get(i))) blockTail = i;
    }
    if (blockTail < 0) return null;

    // No path outside the chain may enter it, and the block may not jump back
    // into the condition work: the arms are independent copies.
    for (int i = 0; i < operations.size(); i++) {
      if (i >= branchIndex && i < joinIndex) continue;
      String target = branchTarget(operations.get(i));
      if (target == null) continue;
      Integer index = labels.get(labelKey(target));
      if (index != null && index > branchIndex && index < joinIndex) return null;
    }
    for (int i = blockIndex; i < joinIndex; i++) {
      String target = branchTarget(operations.get(i));
      if (target == null) continue;
      Integer index = labels.get(labelKey(target));
      if (index != null && index > branchIndex && index < blockIndex) return null;
    }

    StringBuilder source = new StringBuilder();
    boolean complete = true;
    int cursor = branchIndex;
    for (int k = 0; k < conditionals.size(); k++) {
      int conditionIndex = conditionals.get(k);
      RenderResult work = renderRange(operations, cursor, conditionIndex, indent, context, labels);
      source.append(work.source());
      complete &= work.complete();
      boolean toBlock = entersBlock.get(k);
      String condition = normalizeCondition(operations.get(conditionIndex).operands().get(0));
      int armStart = toBlock ? blockIndex : conditionIndex + 1;
      RenderResult arm = renderRange(operations, armStart, joinIndex, indent + 1, context, labels);
      complete &= arm.complete();
      source.append(indent(indent)).append("if (")
          .append(toBlock ? condition : negateCondition(condition)).append(") {\n")
          .append(arm.source())
          .append(indent(indent)).append("}\n");
      cursor = conditionIndex + 1;
      if (!toBlock) {
        // The skipped path leaves for the join while the fall-through still
        // runs the rest of the chain, which the arm just rendered.
        cursor = joinIndex;
        break;
      }
    }
    RenderResult tailWork = renderRange(operations, cursor, joinIndex, indent, context, labels);
    source.append(tailWork.source());
    complete &= tailWork.complete();
    return new ConditionalRender(source.toString(), complete, joinIndex - 1);
  }

  /**
   * Renders a forward-only decision region where a nested branch selects a
   * block that lies past the outer branch target. javac emits this shape when
   * one arm of a nested if is shared with the outer else path, and a plain
   * if/else cannot express the jump into that shared block. The arms are
   * rendered independently, which duplicates the shared block but keeps every
   * path faithful. Regions containing loops, switches, or exception regions are
   * rejected so the regular structurers keep handling them.
   */
  private static ConditionalRender renderForwardDecision(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    if (branch.operands().size() < 2) return null;
    Integer targetIndex = labels.get(labelKey(branch.operands().get(1)));
    if (targetIndex == null || targetIndex <= branchIndex || targetIndex >= end) return null;
    if (!hasBlockBeyond(operations, branchIndex + 1, targetIndex, targetIndex, labels)) {
      return null;
    }
    int[] extent = {branchIndex};
    int[] budget = {256};
    RenderResult source = renderForwardArm(
        operations, branchIndex, end, indent, context, labels, extent, budget, 0);
    if (source == null || extent[0] <= branchIndex) return null;
    // The region must own every block it rendered: an entry into one of those
    // labels from outside would make the block shared with code that was not
    // consumed here.
    for (int i = 0; i < operations.size(); i++) {
      if (i >= branchIndex && i <= extent[0]) continue;
      IrOperationModel operation = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(operation.kind())
          && !"JUMP".equals(operation.kind())) continue;
      if (operation.operands().isEmpty()) continue;
      Integer entry = labels.get(
          labelKey(operation.operands().get(operation.operands().size() - 1)));
      if (entry != null && entry > branchIndex && entry <= extent[0]) return null;
    }
    return new ConditionalRender(source.source(), source.complete(), extent[0]);
  }

  /**
   * True when a nested branch reaches past the outer target and the block in
   * between actually contains code. When only labels separate the two targets
   * the regular nested-if rendering already reaches the shared join.
   */
  private static boolean hasBlockBeyond(
      List<IrOperationModel> operations, int start, int end, int targetIndex,
      Map<String, Integer> labels) {
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(operation.kind())
          || operation.operands().size() < 2) continue;
      Integer target = labels.get(labelKey(operation.operands().get(1)));
      if (target == null || target <= targetIndex) continue;
      for (int between = targetIndex; between < target; between++) {
        if (!isBranchValueMetadata(operations.get(between))) return true;
      }
    }
    return false;
  }

  private static RenderResult renderForwardArm(
      List<IrOperationModel> operations,
      int start,
      int limit,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels,
      int[] extent,
      int[] budget,
      int depth) {
    if (depth > 24) return null;
    StringBuilder source = new StringBuilder();
    boolean complete = true;
    int i = start;
    while (i < limit) {
      if (--budget[0] < 0) return null;
      IrOperationModel operation = operations.get(i);
      String kind = operation.kind();
      if ("TRY_BEGIN".equals(kind) || "TRY_END".equals(kind)
          || "CATCH_BEGIN".equals(kind) || "TABLE_SWITCH".equals(kind)
          || "LOOKUP_SWITCH".equals(kind)) return null;
      if (isBranchValueMetadata(operation)) {
        i++;
        continue;
      }
      extent[0] = Math.max(extent[0], i);
      if ("CONDITIONAL_BRANCH".equals(kind)) {
        if (operation.operands().size() < 2) return null;
        Integer targetIndex = labels.get(labelKey(operation.operands().get(1)));
        if (targetIndex == null || targetIndex <= i || targetIndex >= limit) return null;
        RenderResult taken = renderForwardArm(
            operations, targetIndex, limit, indent + 1, context, labels,
            extent, budget, depth + 1);
        if (taken == null) return null;
        RenderResult fallthrough = renderForwardArm(
            operations, i + 1, limit, indent + 1, context, labels,
            extent, budget, depth + 1);
        if (fallthrough == null) return null;
        source.append(indent(indent)).append("if (")
            .append(normalizeCondition(operation.operands().get(0))).append(") {\n")
            .append(taken.source())
            .append(indent(indent)).append("} else {\n")
            .append(fallthrough.source())
            .append(indent(indent)).append("}\n");
        return new RenderResult(
            source.toString(), taken.complete() && fallthrough.complete());
      }
      if ("JUMP".equals(kind)) {
        if (operation.operands().isEmpty()) return null;
        String target = operation.operands().get(0);
        if (context.isActiveLoop(labelKey(target))) {
          source.append(indent(indent)).append("continue");
          if (context.hasLoopLabel(labelKey(target))) {
            source.append(" ").append(jsLabel(target));
          }
          source.append(";\n");
          return new RenderResult(source.toString(), complete);
        }
        if (context.isBreakTarget(labelKey(target))) {
          if (context.isInnermostBreakTarget(labelKey(target))) {
            source.append(indent(indent)).append("break;\n");
          } else {
            String loopLabel = context.loopExitLabel(labelKey(target));
            if (loopLabel == null) return null;
            source.append(indent(indent)).append("break ")
                .append(jsLabel(loopLabel)).append(";\n");
          }
          return new RenderResult(source.toString(), complete);
        }
        Integer targetIndex = labels.get(labelKey(target));
        if (targetIndex != null
            && (targetIndex == limit || isFallthroughJump(operations, i, targetIndex))) {
          i++;
          continue;
        }
        return null;
      }
      if ("RETURN".equals(kind) || "THROW".equals(kind)) {
        String line = translateOperation(operation, context);
        if (line == null) return null;
        source.append(indent(indent)).append(line).append("\n");
        return new RenderResult(source.toString(), complete);
      }
      String line = translateOperation(operation, context);
      if (line == null) {
        if (isExpressionOnly(operation)) {
          i++;
          continue;
        }
        return null;
      }
      if (!line.isBlank()) source.append(indent(indent)).append(line).append("\n");
      i++;
    }
    return new RenderResult(source.toString(), complete);
  }

  private static ConditionalRender renderConditional(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    return renderConditional(
        operations, branchIndex, end, indent, context, labels, null);
  }

  private static ConditionalRender renderConditional(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels,
      String conditionOverride) {
    IrOperationModel branch = operations.get(branchIndex);
    String condition = conditionOverride != null
        ? conditionOverride : branch.operands().get(0);
    String target = branch.operands().get(1);
    Integer targetIndex = labels.get(labelKey(target));

    // A branch to the exclusive end of a nested render range is a normal
    // structured exit. Render the fall-through path under the inverse
    // condition instead of leaking an unsupported branch marker.
    if (targetIndex == null)
      return null;

    int continueJump = findContinueJumpAtTarget(operations, targetIndex, context);
    if (continueJump >= 0) {
      int skipJoinJump = findForwardJoinJump(
          operations, branchIndex + 1, targetIndex, end, labels);
      if (skipJoinJump >= 0) {
        // The false path jumps around a block whose only action is continuing
        // the active loop. Replace that diamond with a conditional continue.
        RenderResult fallthrough = renderRange(
            operations, branchIndex + 1, skipJoinJump, indent, context, labels);
        StringBuilder source = new StringBuilder();
        source.append(indent(indent)).append("if (")
            .append(normalizeCondition(condition)).append(") continue;\n")
            .append(fallthrough.source());
        int lastIndex = targetIndex >= end ? end - 1 : continueJump;
        return new ConditionalRender(source.toString(), fallthrough.complete(), lastIndex);
      }
      int fallthroughEnd = Math.min(targetIndex, end);
      RenderResult fallthrough = renderRange(
          operations, branchIndex + 1, fallthroughEnd, indent, context, labels);
      StringBuilder source = new StringBuilder();
      source.append(indent(indent)).append("if (")
          .append(normalizeCondition(condition)).append(") continue;\n")
          .append(fallthrough.source());
      int lastIndex = targetIndex >= end ? end - 1 : continueJump;
      return new ConditionalRender(source.toString(), fallthrough.complete(), lastIndex);
    }

    // A nested render range can end before a valid branch destination (for
    // example, an inner if that exits an outer block). In that range, only
    // the fall-through path belongs to the current body, so guard that path
    // with the inverse condition and let the enclosing renderer handle the
    // destination. Do not leak a raw CONDITIONAL_BRANCH marker for this shape.
    if (targetIndex > end) {
      RenderResult body = renderRange(
          operations, branchIndex + 1, end, indent + 1, context, labels);
      StringBuilder source = new StringBuilder();
      source.append(indent(indent))
          .append("if (")
          .append(negateCondition(condition))
          .append(") {\n");
      source.append(body.source());
      source.append(indent(indent)).append("}\n");
      return new ConditionalRender(source.toString(), body.complete(), end - 1);
    }

    if (targetIndex <= branchIndex) {
      if (context.isActiveLoop(labelKey(target))) {
        return new ConditionalRender(
            indent(indent) + "if (" + normalizeCondition(condition) + ") continue;\n",
            true,
            branchIndex);
      }
      return renderBackwardLoop(
          operations, branchIndex, targetIndex, end, indent, context, labels,
          condition);
    }

    int backwardJump = findBackwardJump(operations, branchIndex + 1, targetIndex, labels);
    if (backwardJump >= 0) {
      String loopTarget = operations.get(backwardJump).operands().get(0);
      String normalizedLoopTarget = labelKey(loopTarget);
      Integer loopStart = labels.get(normalizedLoopTarget);
      if (loopStart != null && loopStart <= branchIndex) {
        if (context.isActiveLoop(normalizedLoopTarget)
            && onlyMetadataBetween(operations, backwardJump + 1, targetIndex)) {
          // This branch targets the next block while its fall-through edge
          // immediately continues the enclosing loop. Keep the body block in
          // normal order and replace only the backward goto with continue.
          RenderResult fallthrough = renderRange(
              operations, branchIndex + 1, backwardJump, indent + 1, context, labels);
          StringBuilder source = new StringBuilder();
          source.append(indent(indent)).append("if (")
              .append(negateCondition(condition)).append(") {\n")
              .append(fallthrough.source())
              .append(indent(indent + 1)).append("continue;\n")
              .append(indent(indent)).append("}\n");
          return new ConditionalRender(source.toString(), fallthrough.complete(), backwardJump);
        }
        if (!context.isActiveLoop(normalizedLoopTarget)) {
          boolean labeled = needsLoopLabel(
              operations, branchIndex + 1, backwardJump, normalizedLoopTarget);
          context.enterLoop(normalizedLoopTarget, labeled);
          RenderResult body;
          try {
            body = renderRange(
                operations, branchIndex + 1, backwardJump, indent + 1, context, labels);
          } finally {
            context.exitLoop();
          }
          StringBuilder source = new StringBuilder();
          source.append(indent(indent));
          if (labeled) source.append(jsLabel(normalizedLoopTarget)).append(": ");
          source.append("while (")
              .append(normalizeCondition(condition)).append(") {\n")
              .append(body.source())
              .append(indent(indent)).append("}\n");
          return new ConditionalRender(source.toString(), body.complete(), targetIndex);
        }
      }
    }

    int jumpIndex = findForwardJoinJump(
        operations, branchIndex + 1, targetIndex, end, labels);
    if (jumpIndex >= 0) {
      IrOperationModel jump = operations.get(jumpIndex);
      if (!jump.operands().isEmpty()
          && context.isLoopExit(labelKey(jump.operands().get(0)))
          && onlyConditionWork(operations, branchIndex + 1, jumpIndex, targetIndex, labels)) {
        // The fall-through path immediately leaves the enclosing break-loop
        // while the taken path stays inside: guard the fall-through with a
        // break and render the taken body. A chain of branches that share the
        // same destination forms one guard: every condition has to be false
        // before the loop is left.
        List<String> guardConditions = new java.util.ArrayList<>();
        guardConditions.add(condition);
        for (int scan = jumpIndex - 1; scan > branchIndex; scan--) {
          IrOperationModel previous = operations.get(scan);
          if (isExpressionOnly(previous) || "LABEL".equals(previous.kind())) continue;
          if ("CONDITIONAL_BRANCH".equals(previous.kind())
              && previous.operands().size() >= 2
              && targetIndex.equals(labels.get(labelKey(previous.operands().get(1))))) {
            guardConditions.add(0, previous.operands().get(0));
            continue;
          }
          break;
        }
        RenderResult takenBody = renderRange(
            operations, targetIndex + 1, end, indent, context, labels);
        StringBuilder source = new StringBuilder();
        source.append(indent(indent)).append("if (");
        for (int g = 0; g < guardConditions.size(); g++) {
          if (g > 0) source.append(" && ");
          source.append("(!(").append(normalizeCondition(guardConditions.get(g))).append("))");
        }
        source.append(") break;\n")
            .append(takenBody.source());
        return new ConditionalRender(source.toString(), takenBody.complete(), end - 1);
      }
      if (!jump.operands().isEmpty()
          && !context.isLoopExit(labelKey(jump.operands().get(0)))) {
        Integer endIndex = labels.get(labelKey(jump.operands().get(0)));
        if (endIndex != null && endIndex > targetIndex) {
          // A nested if may join at a label outside the current render range
          // (for example, both arms exit to an enclosing if/loop join). Render
          // only the portion owned by this range, leaving the enclosing renderer
          // to consume the shared continuation.
          int structuredEnd = Math.min(endIndex, end);
          int tryBeginBeforeJoin = findTryBeginBeforeTarget(
              operations, branchIndex + 1, endIndex, jump.operands().get(0), labels);
          if (tryBeginBeforeJoin >= 0 && tryBeginBeforeJoin < structuredEnd) {
            structuredEnd = tryBeginBeforeJoin;
          }
          int tryBeginBeforeTarget = findTryBeginBeforeTarget(
              operations, branchIndex + 1, targetIndex, target, labels);
          int targetBodyStart = tryBeginBeforeTarget >= 0
              ? tryBeginBeforeTarget : targetIndex + 1;
          // A try in the fall-through arm emits its catch handler after the
          // normal-path jump. Keep that handler in the arm instead of clipping
          // the try at the jump; the handler ends at the other branch target.
          boolean hasTryInFallthrough = false;
          for (int i = branchIndex + 1; i < jumpIndex; i++) {
            if ("TRY_BEGIN".equals(operations.get(i).kind())) {
              hasTryInFallthrough = true;
              break;
            }
          }
          boolean hasCatchBeforeTarget = false;
          for (int i = jumpIndex; i < targetIndex; i++) {
            if ("CATCH_BEGIN".equals(operations.get(i).kind())) {
              hasCatchBeforeTarget = true;
              break;
            }
          }
          int fallthroughEnd = hasTryInFallthrough && hasCatchBeforeTarget
              ? Math.min(targetIndex, structuredEnd) : jumpIndex;
          RenderResult fallthroughBody = renderRange(
              operations, branchIndex + 1, fallthroughEnd, indent + 1, context, labels);
          RenderResult targetBody = renderRange(
              operations, targetBodyStart, structuredEnd, indent + 1, context, labels);

          StringBuilder source = new StringBuilder();
          source.append(indent(indent))
              .append("if (")
              .append(normalizeCondition(condition))
              .append(") {\n");
          source.append(targetBody.source());
          source.append(indent(indent)).append("} else {\n");
          source.append(fallthroughBody.source());
          source.append(indent(indent)).append("}\n");

          return new ConditionalRender(
              source.toString(),
              fallthroughBody.complete() && targetBody.complete(),
              structuredEnd - 1);
        }
      }
    }

    // Exception-region markers are inserted immediately before their start
    // label. If that label is this branch's destination, keep those markers out
    // of the fall-through block so the enclosing range can structure the try
    // region with its full extent rather than clipping it at the label.
    int fallthroughEnd = findTryBeginBeforeTarget(
        operations, branchIndex + 1, targetIndex, target, labels);
    if (fallthroughEnd < 0) fallthroughEnd = targetIndex;
    RenderResult body = renderRange(
        operations, branchIndex + 1, fallthroughEnd, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent))
        .append("if (")
        .append(negateCondition(condition))
        .append(") {\n");
    source.append(body.source());
    source.append(indent(indent)).append("}\n");

    // Leave the branch target label itself for the enclosing range: the label
    // may be a loop header, and consuming it here would hide the loop's back
    // edges from the loop finders.
    int lastIndex = fallthroughEnd < targetIndex ? fallthroughEnd - 1 : targetIndex - 1;
    return new ConditionalRender(source.toString(), body.complete(), lastIndex);
  }

  private static int findTryBeginBeforeTarget(
      List<IrOperationModel> operations,
      int start,
      int targetIndex,
      String targetLabel,
      Map<String, Integer> labels) {
    if (labels.getOrDefault(labelKey(targetLabel), -1) != targetIndex) return -1;
    for (int i = start; i < targetIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"TRY_BEGIN".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      if (labelKey(targetLabel).equals(labelKey(operation.operands().get(0)))) return i;
    }
    return -1;
  }

  private static ConditionalRender renderBackwardLoop(
      List<IrOperationModel> operations,
      int branchIndex,
      int targetIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels,
      String condition) {
    String loopLabel = labelKey(operations.get(targetIndex).operands().get(0));
    String exitLabel = findDoWhileExitLabel(
        operations, targetIndex, branchIndex, end, labels);
    boolean labeled = needsLoopLabel(
        operations, targetIndex + 1, branchIndex, loopLabel)
        || needsExitBreakLabel(
            operations, targetIndex + 1, branchIndex, exitLabel);
    context.enterLoop(loopLabel, labeled);
    if (exitLabel != null) context.enterLoopExit(exitLabel);
    RenderResult body;
    try {
      body = renderRange(
          operations, targetIndex + 1, branchIndex, indent + 1, context, labels);
    } finally {
      if (exitLabel != null) context.exitLoopExit();
      context.exitLoop();
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("do {\n");
    source.append(body.source());
    source.append(indent(indent))
        .append("} while (")
        .append(normalizeCondition(condition))
        .append(");\n");

    return new ConditionalRender(source.toString(), body.complete(), branchIndex);
  }

  /**
   * Finds the continuation label a do-while leaves through when the body also
   * jumps there early. The label is accepted only when the body actually uses
   * it, so unrelated labels after the condition are not treated as exits.
   */
  private static String findDoWhileExitLabel(
      List<IrOperationModel> operations,
      int headerIndex,
      int branchIndex,
      int end,
      Map<String, Integer> labels) {
    int exitIndex = -1;
    for (int i = branchIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind())) {
        exitIndex = i;
        break;
      }
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        Integer target = labels.get(labelKey(operation.operands().get(0)));
        if (target != null && target > branchIndex && target < end
            && "LABEL".equals(operations.get(target).kind())) {
          exitIndex = target;
        }
        break;
      }
      if (!isLoopExitMetadata(operation)) break;
    }
    if (exitIndex < 0 || operations.get(exitIndex).operands().isEmpty()) return null;
    String exitLabel = labelKey(operations.get(exitIndex).operands().get(0));
    for (int i = headerIndex + 1; i < branchIndex; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target != null && exitLabel.equals(labelKey(target))) return exitLabel;
    }
    return null;
  }

  /**
   * Recognizes a loop whose condition branches to an exit label and whose
   * back-edge occurs after the body. This handles nested loops and the common
   * while/for bytecode shape where the exit test is not adjacent to the back-edge.
   */
  private static LoopShape findForwardExitLoop(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      String loopLabel,
      Map<String, Integer> labels) {
    Integer header = labels.get(labelKey(loopLabel));
    if (header == null || header != loopHeaderIndex) return null;

    // The loop's exit test is part of the header block: it must precede the
    // first jump back to the header, otherwise it belongs to a nested structure
    // that only happens to end near a back edge.
    int firstBackEdge = end;
    for (int i = loopHeaderIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) {
        firstBackEdge = i;
        break;
      }
    }

    for (int branchIndex = loopHeaderIndex + 1; branchIndex < Math.min(end, firstBackEdge); branchIndex++) {
      IrOperationModel branch = operations.get(branchIndex);
      if (!"CONDITIONAL_BRANCH".equals(branch.kind()) || branch.operands().size() < 2) continue;
      Integer exitIndex = labels.get(labelKey(branch.operands().get(1)));
      if (exitIndex == null || exitIndex <= branchIndex) continue;

      int backEdgeIndex = Math.min(exitIndex - 1, end - 1);
      while (backEdgeIndex > branchIndex && isLoopExitMetadata(operations.get(backEdgeIndex))) {
        backEdgeIndex--;
      }
      if (backEdgeIndex <= branchIndex) continue;

      IrOperationModel backEdge = operations.get(backEdgeIndex);
      boolean backEdgeMatches = "JUMP".equals(backEdge.kind()) && !backEdge.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(backEdge.operands().get(0)));
      if (backEdgeMatches) {
        // The exit block must be the loop's continuation, not another nested
        // region that happens to flow back to the header.
        boolean reentersLoop = false;
        for (int i = exitIndex; i < end; i++) {
          IrOperationModel operation = operations.get(i);
          String target = null;
          if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
            target = operation.operands().get(0);
          } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
              && operation.operands().size() >= 2) {
            target = operation.operands().get(1);
          }
          if (target != null && labelKey(loopLabel).equals(labelKey(target))) {
            reentersLoop = true;
            break;
          }
        }
        if (reentersLoop) continue;
        // A conditional before the accepted exit test can be the real loop
        // condition: its target then lies past the whole continuation block,
        // which the accepted test only breaks out of. Prefer that condition so
        // the loop leaves through a labeled block instead of falling into the
        // continuation.
        for (int i = loopHeaderIndex + 1; i < branchIndex; i++) {
          IrOperationModel earlier = operations.get(i);
          if (!"CONDITIONAL_BRANCH".equals(earlier.kind()) || earlier.operands().size() < 2) continue;
          Integer earlierTarget = labels.get(labelKey(earlier.operands().get(1)));
          if (earlierTarget != null && earlierTarget > exitIndex) {
            return new LoopShape(i, backEdgeIndex, earlierTarget);
          }
        }
        return new LoopShape(branchIndex, backEdgeIndex, exitIndex);
      }
    }
    return null;
  }

  /**
   * A loop body can contain a nested loop whose back edge lies after the outer
   * loop's own back edge: javac rotates loops so the outer continue is emitted
   * before the inner loop's tail. The outer body then extends to the end of
   * that nested loop. Returns the last index of the extended body, or -1 when
   * the body already ends at its back edge.
   */
  private static int extendLoopPastNestedLoops(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int backEdgeIndex,
      int end,
      Map<String, Integer> labels) {
    int loopEnd = backEdgeIndex;
    boolean extended = false;
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = loopEnd + 1; i < end; i++) {
        IrOperationModel operation = operations.get(i);
        if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
        Integer target = labels.get(labelKey(operation.operands().get(0)));
        if (target == null || target <= loopHeaderIndex || target > loopEnd) continue;
        // The jump back to a label inside the loop is a nested loop's back
        // edge; that nested loop runs to its last edge.
        int innerEnd = i;
        for (int j = i + 1; j < end; j++) {
          IrOperationModel later = operations.get(j);
          if ("JUMP".equals(later.kind()) && !later.operands().isEmpty()
              && target.equals(labels.get(labelKey(later.operands().get(0))))) {
            innerEnd = j;
          }
        }
        loopEnd = innerEnd;
        extended = true;
        changed = true;
        break;
      }
    }
    return extended ? loopEnd : -1;
  }

  private static boolean isLoopExitMetadata(IrOperationModel operation) {    return "LABEL".equals(operation.kind()) || "TRY_BEGIN".equals(operation.kind())
        || "TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind())
        || "STACK_OPERATION".equals(operation.kind());
  }

  /**
   * Accepts a {@code while (true)} whose back edge is not the range's last
   * instruction because a switch or catch handler continues after it. Only
   * applies when the range ends in return/throw, so wrapping the whole range
   * cannot swallow a fall-out path.
   */
  private static boolean hasOpenInfiniteLoopBackEdge(
      List<IrOperationModel> operations, int loopHeaderIndex, int end, String loopLabel) {
    if (loopHeaderIndex + 2 >= end) return false;
    if (!isTerminal(operations.get(end - 1))) return false;
    for (int i = loopHeaderIndex + 1; i < end - 1; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when a protected region's start label is also a loop header: some
   * later instruction in this range jumps back to it.
   */
  private static boolean tryRegionStartsLoop(
      List<IrOperationModel> operations,
      int begin,
      int end,
      String startLabel,
      Map<String, Integer> labels) {
    Integer header = labels.get(labelKey(startLabel));
    if (header == null || header < begin || header >= end) return false;
    for (int i = header + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(startLabel).equals(labelKey(operation.operands().get(0)))) {
        return true;
      }
    }
    return false;
  }

  private static ConditionalRender renderOpenInfiniteLoop(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    boolean labeled = needsLoopLabel(operations, loopHeaderIndex + 1, end, loopLabel);
    context.enterLoop(loopLabel, labeled);
    RenderResult body;
    try {
      body = renderRange(
          operations, loopHeaderIndex + 1, end, indent + 1, context, labels);
    } finally {
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (true) {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), end - 1);
  }

  private static LoopShape findInfiniteLoopBackEdge(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      String loopLabel,
      Map<String, Integer> labels) {
    Integer header = labels.get(labelKey(loopLabel));
    if (header == null || header != loopHeaderIndex || end <= loopHeaderIndex + 1) return null;
    int backEdgeIndex = end - 1;
    IrOperationModel operation = operations.get(backEdgeIndex);
    if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()
        || !labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) return null;
    return new LoopShape(-1, backEdgeIndex, end);
  }

  private static ConditionalRender renderInfiniteLoop(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      LoopShape loop,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    boolean labeled = needsLoopLabel(
        operations, loopHeaderIndex + 1, loop.backEdgeIndex(), loopLabel);
    context.enterLoop(loopLabel, labeled);
    RenderResult body;
    try {
      body = renderRange(
          operations, loopHeaderIndex + 1, loop.backEdgeIndex(), indent + 1, context, labels);
    } finally {
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (true) {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), loop.backEdgeIndex());
  }

  /**
   * Finds the loop shape javac emits for {@code while (true)} bodies that leave
   * early: the last jump back to the header is the loop's back edge and the
   * first label after it is the exit target used by break edges. The shape is
   * only accepted when the body actually branches or jumps to that exit label,
   * so ordinary infinite loops keep their existing rendering.
   */
  private static LoopShape findBreakLoopBackEdge(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      String loopLabel,
      Map<String, Integer> labels) {
    Integer header = labels.get(labelKey(loopLabel));
    if (header == null || header != loopHeaderIndex) return null;
    int backEdgeIndex = -1;
    for (int i = loopHeaderIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) {
        backEdgeIndex = i;
      }
    }
    if (backEdgeIndex < 0) return null;
    int exitIndex = -1;
    for (int i = backEdgeIndex + 1; i < end; i++) {
      if ("LABEL".equals(operations.get(i).kind())) {
        exitIndex = i;
        break;
      }
    }
    if (exitIndex < 0) return null;
    String exitLabel = labelKey(operations.get(exitIndex).operands().get(0));
    boolean hasEarlyExit = false;
    for (int i = loopHeaderIndex + 1; i < backEdgeIndex; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target != null && exitLabel.equals(labelKey(target))) {
        hasEarlyExit = true;
        break;
      }
    }
    if (!hasEarlyExit) return null;
    return new LoopShape(-1, backEdgeIndex, exitIndex);
  }

  private static ConditionalRender renderBreakLoop(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      LoopShape loop,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    String exitLabel = labelKey(operations.get(loop.exitIndex()).operands().get(0));
    boolean labeled = needsLoopLabel(
        operations, loopHeaderIndex + 1, loop.backEdgeIndex(), loopLabel)
        || needsExitBreakLabel(
            operations, loopHeaderIndex + 1, loop.backEdgeIndex(), exitLabel);
    context.enterLoop(loopLabel, labeled);
    context.enterLoopExit(exitLabel);
    RenderResult body;
    try {
      body = renderRange(
          operations, loopHeaderIndex + 1, loop.backEdgeIndex(), indent + 1, context, labels);
    } finally {
      context.exitLoopExit();
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (true) {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), loop.backEdgeIndex());
  }

  /**
   * A {@code while} loop whose condition short-circuits by jumping into the
   * body: {@code L: if (a) goto body; if (b) goto body; goto exit; body: ...}.
   * The body flows back to L, re-evaluating the whole condition. This is the
   * shape javac emits for {@code while (a || b)}.
   */
  private static ConditionalRender renderConditionJumpLoop(
      List<IrOperationModel> operations, int loopHeaderIndex, int end,
      int indent, TranslationContext context, Map<String, Integer> labels) {
    if (operations.get(loopHeaderIndex).operands().isEmpty()) return null;
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    Integer header = labels.get(labelKey(loopLabel));
    if (header == null || header != loopHeaderIndex) return null;

    List<String> conditions = new java.util.ArrayList<>();
    int bodyIndex = -1;
    int jumpIndex = -1;
    for (int i = loopHeaderIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind()) && operation.operands().size() >= 2) {
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null || target <= i) return null;
        if (bodyIndex < 0) {
          bodyIndex = target;
        } else if (bodyIndex != target) {
          return null;
        }
        conditions.add(operation.operands().get(0));
        continue;
      }
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        jumpIndex = i;
        break;
      }
      if (!isBranchValueMetadata(operation) && !isExpressionOnly(operation)) return null;
    }
    if (conditions.isEmpty() || jumpIndex < 0 || bodyIndex <= jumpIndex
        || bodyIndex >= end) return null;
    for (int i = jumpIndex + 1; i < bodyIndex; i++) {
      if (!isLoopExitMetadata(operations.get(i))) return null;
    }
    Integer exitIndex = labels.get(labelKey(operations.get(jumpIndex).operands().get(0)));
    if (exitIndex == null || exitIndex <= bodyIndex || exitIndex > end) return null;

    // The body must flow back to the condition block, otherwise this is a
    // two-way branch rather than a loop.
    boolean backEdge = false;
    for (int i = bodyIndex; i < exitIndex; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) {
        backEdge = true;
        break;
      }
    }
    if (!backEdge) return null;

    String condition;
    if (conditions.size() == 1) {
      condition = normalizeCondition(conditions.get(0));
    } else {
      StringBuilder combined = new StringBuilder("(");
      for (int k = 0; k < conditions.size(); k++) {
        if (k > 0) combined.append(" || ");
        combined.append(normalizeCondition(conditions.get(k)));
      }
      condition = combined.append(")").toString();
    }

    String exitLabel = labelKey(operations.get(exitIndex).operands().get(0));
    boolean labeled = needsLoopLabel(operations, bodyIndex, exitIndex, loopLabel)
        || needsExitBreakLabel(operations, bodyIndex, exitIndex, exitLabel);
    context.enterLoop(loopLabel, labeled);
    context.enterLoopExit(exitLabel);
    RenderResult body;
    try {
      body = renderRange(operations, bodyIndex, exitIndex, indent + 1, context, labels);
    } finally {
      context.exitLoopExit();
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (").append(condition).append(") {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), exitIndex - 1);
  }

  /**
   * True when the label heads a {@code while (true)} loop whose back edges sit
   * inside the body and whose exits are all terminal blocks (returns/throws).
   * Such a loop never falls through, so the enclosing range tail is its body.
   */
  private static boolean findTerminalExitLoop(
      List<IrOperationModel> operations, int loopHeaderIndex, int end,
      String loopLabel, Map<String, Integer> labels) {
    Integer header = labels.get(labelKey(loopLabel));
    if (header == null || header != loopHeaderIndex || end <= loopHeaderIndex + 1) return false;

    int lastBackEdge = -1;
    for (int i = loopHeaderIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) {
        lastBackEdge = i;
      }
    }
    if (lastBackEdge < 0) return false;

    for (int i = loopHeaderIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      int targetOperand;
      if ("JUMP".equals(operation.kind())) {
        targetOperand = 0;
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        targetOperand = 1;
      } else {
        continue;
      }
      if (operation.operands().size() <= targetOperand) continue;
      Integer targetIndex = labels.get(labelKey(operation.operands().get(targetOperand)));
      if (targetIndex == null || targetIndex == loopHeaderIndex
          || targetIndex <= lastBackEdge) continue;
      // Code reached only past the loop's last back edge is continuation code:
      // rendering it inside the loop would change what executes. Only accept
      // targets whose block ends in return/throw, which the branch renderers
      // inline as guards.
      if (!isTerminalBlock(operations, targetIndex)) return false;
    }
    return true;
  }

  private static ConditionalRender renderTerminalExitLoop(
      List<IrOperationModel> operations, int loopHeaderIndex, int end,
      int indent, TranslationContext context, Map<String, Integer> labels) {
    if (operations.get(loopHeaderIndex).operands().isEmpty()) return null;
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    boolean labeled = needsLoopLabel(operations, loopHeaderIndex + 1, end, loopLabel);
    context.enterLoop(loopLabel, labeled);
    RenderResult body;
    try {
      body = renderRange(operations, loopHeaderIndex + 1, end, indent + 1, context, labels);
    } finally {
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (true) {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), end - 1);
  }

  /** A straight-line block ending in return/throw, reached only through its label. */
  private static boolean isTerminalBlock(List<IrOperationModel> operations, int start) {
    if (start < 0 || start >= operations.size()) return false;
    if (!"LABEL".equals(operations.get(start).kind())) return false;
    for (int i = start + 1; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);
      if ("RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
          || "TERMINATE".equals(operation.kind())) {
        return true;
      }
      if ("LABEL".equals(operation.kind()) || "TRY_BEGIN".equals(operation.kind())
          || "TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind())
          || "JUMP".equals(operation.kind()) || "CONDITIONAL_BRANCH".equals(operation.kind())
          || "TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) {
        return false;
      }
    }
    return false;
  }

  private static ConditionalRender renderForwardExitLoop(
      List<IrOperationModel> operations,
      int loopHeaderIndex,
      int end,
      LoopShape loop,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    String loopLabel = labelKey(operations.get(loopHeaderIndex).operands().get(0));
    String exitLabel = loop.exitIndex() < operations.size()
        && !operations.get(loop.exitIndex()).operands().isEmpty()
        ? labelKey(operations.get(loop.exitIndex()).operands().get(0)) : null;
    String condition = operations.get(loop.branchIndex()).operands().get(0);
    int extendedBodyEnd = extendLoopPastNestedLoops(
        operations, loopHeaderIndex, loop.backEdgeIndex(), end, labels);
    if (extendedBodyEnd >= 0) {
      // A nested loop continues past this loop's back edge, so the branch this
      // shape was found through points into the body instead of leaving the
      // loop. Render the full body and let the branch structure itself.
      boolean labeled = needsLoopLabel(
          operations, loopHeaderIndex + 1, extendedBodyEnd + 1, loopLabel)
          || needsExitBreakLabel(
              operations, loopHeaderIndex + 1, extendedBodyEnd + 1, exitLabel);
      context.enterLoop(loopLabel, labeled);
      if (exitLabel != null) context.enterLoopExit(exitLabel);
      RenderResult body;
      try {
        body = renderRange(
            operations, loopHeaderIndex + 1, extendedBodyEnd + 1, indent + 1, context, labels);
      } finally {
        if (exitLabel != null) context.exitLoopExit();
        context.exitLoop();
      }
      StringBuilder source = new StringBuilder();
      source.append(indent(indent));
      if (labeled) source.append(jsLabel(loopLabel)).append(": ");
      source.append("while (true) {\n")
          .append(body.source())
          .append(indent(indent)).append("}\n");
      return new ConditionalRender(source.toString(), body.complete(), extendedBodyEnd);
    }
    // The header exit leaves past the loop's continuation block, which only a
    // labeled block can express. That block is only this renderer's business
    // when the exit still lies inside the range it was asked to render: at the
    // range end the caller emits the continuation itself.
    boolean farExit = loop.exitIndex() > loop.backEdgeIndex() + 1 && loop.exitIndex() < end;
    String skipLabel = farExit ? "_loopExit" + loopHeaderIndex : null;
    String continuationLabel = null;
    int rawContinuationStart = loop.backEdgeIndex() + 1;
    int continuationStart = rawContinuationStart;
    if (farExit) {
      while (continuationStart < loop.exitIndex()) {
        IrOperationModel candidate = operations.get(continuationStart);
        if ("LABEL".equals(candidate.kind())) break;
        if (!isLoopExitMetadata(candidate)) break;
        continuationStart++;
      }
      if (continuationStart < loop.exitIndex()
          && "LABEL".equals(operations.get(continuationStart).kind())
          && !operations.get(continuationStart).operands().isEmpty()) {
        continuationLabel = labelKey(operations.get(continuationStart).operands().get(0));
      }
    }
    // A loop can exit onto the start of a protected region. That region's
    // TRY_END and catch handler lie past this loop's continuation range, so
    // rendering it inside the labeled exit block would truncate the try. Leave
    // the region for the enclosing range, which can see its full extent.
    boolean continuationStartsProtectedRegion = false;
    if (farExit && exitLabel != null) {
      for (int i = rawContinuationStart; i < loop.exitIndex(); i++) {
        IrOperationModel candidate = operations.get(i);
        if ("TRY_BEGIN".equals(candidate.kind()) && !candidate.operands().isEmpty()
            && labelKey(candidate.operands().get(0)).equals(labelKey(exitLabel))) {
          continuationStartsProtectedRegion = true;
          break;
        }
      }
    }
    boolean labeled = needsLoopLabel(
        operations, loopHeaderIndex + 1, loop.backEdgeIndex(), loopLabel)
        || needsExitBreakLabel(
            operations, loopHeaderIndex + 1, loop.backEdgeIndex(), exitLabel);
    context.enterLoop(loopLabel, labeled);
    if (exitLabel != null) {
      if (farExit) context.enterSkipExit(exitLabel, skipLabel);
      else context.enterLoopExit(exitLabel);
    }
    if (continuationLabel != null) context.enterLoopExit(continuationLabel);
    RenderResult conditionPrelude;
    RenderResult body;
    try {
      // Keep condition setup inside the loop so side-effecting local writes are
      // re-executed each iteration. The conditional branch becomes a break
      // guard because its target is the loop's forward exit.
      conditionPrelude = renderRange(
          operations, loopHeaderIndex + 1, loop.branchIndex(), indent + 1, context, labels);
      body = renderRange(
          operations, loop.branchIndex() + 1, loop.backEdgeIndex(), indent + 1, context, labels);
    } finally {
      if (continuationLabel != null) context.exitLoopExit();
      if (exitLabel != null) {
        if (farExit) context.exitSkipExit();
        else context.exitLoopExit();
      }
      context.exitLoop();
    }

    StringBuilder source = new StringBuilder();
    if (farExit) source.append(indent(indent)).append(jsLabel(skipLabel)).append(": {\n");
    source.append(indent(indent));
    if (labeled) source.append(jsLabel(loopLabel)).append(": ");
    source.append("while (true) {\n")
        .append(conditionPrelude.source())
        .append(indent(indent + 1)).append("if (")
        .append(normalizeCondition(condition)).append(") ")
        .append(farExit ? "break " + jsLabel(skipLabel) + ";" : "break;").append("\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    boolean complete = conditionPrelude.complete() && body.complete();
    if (farExit) {
      // The header exit leaves past the loop's continuation block, which only
      // a labeled block can express. Render that block inside the label so a
      // plain break still lands on the loop's normal continuation. A protected
      // region that starts exactly at the exit is rendered by the caller.
      if (!continuationStartsProtectedRegion) {
        RenderResult continuation = renderRange(
            operations, loop.backEdgeIndex() + 1, loop.exitIndex(), indent + 1, context, labels);
        source.append(continuation.source());
        complete &= continuation.complete();
      }
      source.append(indent(indent)).append("}\n");
    }
    // Resume at the exit label instead of skipping it: the label can be the
    // header of a following loop, and its block still needs to be structured.
    int lastIndex = continuationStartsProtectedRegion
        ? loop.backEdgeIndex()
        : (loop.exitIndex() < end ? loop.exitIndex() - 1 : end - 1);
    return new ConditionalRender(
        source.toString(), complete, lastIndex);
  }

  private static int findBackwardConditional(
      List<IrOperationModel> operations,
      int labelIndex,
      int end,
      String label) {
    // A do-while condition is the loop's only edge back to its header: every
    // continue in such a loop jumps to the condition, not to the header, so
    // additional references mean this label is a plain continue target.
    int references = 0;
    for (int i = labelIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target != null && labelKey(label).equals(labelKey(target))) references++;
    }
    if (references == 1) {
      for (int i = labelIndex + 1; i < end; i++) {
        IrOperationModel operation = operations.get(i);
        if ("CONDITIONAL_BRANCH".equals(operation.kind())
            && operation.operands().size() >= 2
            && labelKey(label).equals(labelKey(operation.operands().get(1))))
          return i;
      }
      return -1;
    }
    // A do-while can also contain explicit continue edges back to the header.
    // Only a single conditional reference is accepted, and it has to be the
    // final reference: otherwise the conditional is an early continue guard,
    // not the loop's bottom condition.
    int lastConditional = -1;
    int conditionalReferences = 0;
    int lastReference = -1;
    for (int i = labelIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      String target = null;
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        target = operation.operands().get(0);
      } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        target = operation.operands().get(1);
      }
      if (target != null && labelKey(label).equals(labelKey(target))) {
        lastReference = i;
        if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
          conditionalReferences++;
          lastConditional = i;
        }
      }
    }
    return conditionalReferences == 1 && lastReference == lastConditional
        ? lastConditional : -1;
  }

  /**
   * Finds the conventional JVM loop shape where the exit test branches out
   * and the fall-through path ends in an unconditional jump back to the loop
   * header. The conditional is returned only when the back edge is immediate;
   * more ambiguous graphs remain explicitly incomplete.
   */
  private static int findUnconditionalBackEdgeConditional(
      List<IrOperationModel> operations,
      int labelIndex,
      int end,
      String loopLabel,
      Map<String, Integer> labels) {
    for (int i = labelIndex + 1; i + 1 < end; i++) {
      IrOperationModel conditional = operations.get(i);
      if (!"CONDITIONAL_BRANCH".equals(conditional.kind())
          || conditional.operands().size() < 2) continue;

      Integer exitIndex = labels.get(labelKey(conditional.operands().get(1)));
      if (exitIndex == null || exitIndex <= i) continue;

      IrOperationModel backEdge = operations.get(i + 1);
      if (!"JUMP".equals(backEdge.kind()) || backEdge.operands().isEmpty()
          || !labelKey(loopLabel).equals(labelKey(backEdge.operands().get(0)))) continue;
      // The exit block is the loop's continuation. A block that flows back to
      // the header means this branch and back edge are a continue chain inside
      // a larger loop, not a loop of their own.
      boolean reentersLoop = false;
      for (int j = exitIndex; j < end; j++) {
        IrOperationModel operation = operations.get(j);
        String target = null;
        if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
          target = operation.operands().get(0);
        } else if ("CONDITIONAL_BRANCH".equals(operation.kind())
            && operation.operands().size() >= 2) {
          target = operation.operands().get(1);
        }
        if (target != null && labelKey(loopLabel).equals(labelKey(target))) {
          reentersLoop = true;
          break;
        }
      }
      if (!reentersLoop) return i;
    }
    return -1;
  }

  private static int findForwardJoinJump(
      List<IrOperationModel> operations,
      int start,
      int branchTargetIndex,
      int end,
      Map<String, Integer> labels) {
    // A nested if/switch can contain its own goto before the outer if's join.
    // Scan backward from the branch target so we prefer the final join edge
    // immediately preceding that target, not the first unrelated jump.
    for (int i = Math.min(branchTargetIndex - 1, end - 1); i >= start; i--) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      Integer joinIndex = labels.get(labelKey(operation.operands().get(0)));
      if (joinIndex != null && joinIndex > branchTargetIndex) return i;
    }
    return -1;
  }

  private static boolean isSimpleNormalCompletion(
      List<IrOperationModel> operations, int start, int end) {
    boolean terminalSeen = false;
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (isBranchValueMetadata(operation)) continue;
      String kind = operation.kind();
      if ("RETURN".equals(kind) || "THROW".equals(kind) || "TERMINATE".equals(kind)) {
        if (terminalSeen) return false;
        terminalSeen = true;
        continue;
      }
      if (terminalSeen) return false;
      if ("METHOD_CALL".equals(kind) || isExpressionOnly(operation)) continue;
      return false;
    }
    return true;
  }

  /**
   * True when leaving a catch clause reaches the jump's destination: the
   * destination is the point where the enclosing try/catch resumes, possibly
   * behind only labels and exception-table metadata.
   */
  private static boolean fallsThroughTo(
      int targetIndex, int resumeIndex, List<IrOperationModel> operations) {
    if (targetIndex == resumeIndex) return true;
    int from = Math.min(targetIndex, resumeIndex);
    int to = Math.max(targetIndex, resumeIndex);
    for (int i = from; i < to; i++) {
      if ("LABEL".equals(operations.get(i).kind())) continue;
      if (!isLoopExitMetadata(operations.get(i))) return false;
    }
    return true;
  }

  /**
   * A jump destination may be the start label of an exception region. Widening
   * the destination to the region record keeps a try/catch that the compiler
   * attributed to that block.
   */
  private static int inlinedBlockStart(
      List<IrOperationModel> operations, int targetIndex, Map<String, Integer> labels) {
    int start = targetIndex;
    while (start > 0 && "TRY_BEGIN".equals(operations.get(start - 1).kind())) {
      List<String> metadata = operations.get(start - 1).operands();
      if (metadata.size() < 3
          || !Integer.valueOf(targetIndex).equals(
              labels.get(labelKey(metadata.get(0))))) break;
      start--;
    }
    return start;
  }

  /**
   * Jump destinations can only be rendered in place when the whole method tail
   * is available and ends in return/throw; otherwise the rendered copy could
   * silently fall out of the enclosing range.
   */
  private static boolean canInlineJumpBlock(
      List<IrOperationModel> operations, int end) {
    return end == operations.size() && end > 0
        && isTerminal(operations.get(end - 1));
  }

  private static boolean onlyMetadataBetween(
      List<IrOperationModel> operations, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!isBranchValueMetadata(operations.get(i))) return false;
    }
    return true;
  }

  private static int findBackwardJump(
      List<IrOperationModel> operations, int start, int end,
      Map<String, Integer> labels) {
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (!"JUMP".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      Integer target = labels.get(labelKey(operation.operands().get(0)));
      if (target != null && target < i) return i;
    }
    return -1;
  }

  /** Normalizes label references from bytecode dumps (for example "[L12]") to label IDs. */
  private static String labelKey(String label) {
    if (label == null) return "";
    String normalized = label.trim();
    while (normalized.length() >= 2 && normalized.startsWith("[") && normalized.endsWith("]"))
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    if (normalized.endsWith(":")) normalized = normalized.substring(0, normalized.length() - 1).trim();
    return normalized;
  }

  /**
   * JavaScript label for a JVM label. Keep identifier-safe characters only;
   * bytecode labels are already simple, this only guards against malformed
   * input.
   */
  private static String jsLabel(String label) {
    String key = labelKey(label);
    StringBuilder safe = new StringBuilder();
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$') safe.append(c);
    }
    if (safe.isEmpty()) return "_label";
    if (Character.isDigit(safe.charAt(0))) safe.insert(0, '_');
    return safe.toString();
  }

  /**
   * True when a loop body contains a jump back to its own header. Such a jump
   * can only come from a nested loop, so the loop needs a JavaScript label for
   * {@code continue} to keep binding to this loop.
   */
  private static boolean needsLoopLabel(
      List<IrOperationModel> operations, int bodyStart, int bodyEnd, String loopLabel) {
    for (int i = bodyStart; i < bodyEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(operation.operands().get(0)))) return true;
    }
    return false;
  }

  /**
   * True when the instructions between a branch and its fall-through jump only
   * contribute to the branch conditions. A guard {@code if (!cond) break;}
   * replaces the fall-through, so it is only safe when the fall-through has no
   * statement effects (a discarded call or a local write still has to run).
   */
  private static boolean onlyConditionWork(
      List<IrOperationModel> operations, int start, int endExclusive,
      int targetIndex, Map<String, Integer> labels) {
    for (int i = start; i < endExclusive; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind())) continue;
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) {
        if (operation.operands().size() < 2) return false;
        Integer target = labels.get(labelKey(operation.operands().get(1)));
        if (target == null || !target.equals(targetIndex)) return false;
        continue;
      }
      if (!isExpressionOnly(operation) || "DROP".equals(operation.kind())) return false;
    }
    return true;
  }

  /**
   * True when the body jumps straight to the loop's exit label. A nested switch
   * or loop would swallow a plain break, so such a loop needs a JavaScript
   * label for the exit jump.
   */
  private static boolean needsExitBreakLabel(
      List<IrOperationModel> operations, int bodyStart, int bodyEnd, String exitLabel) {
    if (exitLabel == null) return false;
    for (int i = bodyStart; i < bodyEnd; i++) {
      IrOperationModel operation = operations.get(i);
      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()
          && labelKey(exitLabel).equals(labelKey(operation.operands().get(0)))) return true;
    }
    return false;
  }

  private static Map<String, Integer> findLabels(
      List<IrOperationModel> operations,
      int start,
      int end) {
    Map<String, Integer> labels = new HashMap<>();
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty())
        labels.put(labelKey(operation.operands().get(0)), i);
    }
    return labels;
  }

  private static String translateOperation(
      IrOperationModel operation,
      TranslationContext context) {
    List<String> operands = operation.operands();

    return switch (operation.kind()) {
      case "LABEL" -> "";
      case "DROP" -> translateDrop(operands);
      case "STACK_OPERATION" -> "";
      case "CALL_VALUE" -> null;
      case "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE",
          "ARRAY_READ", "ARRAY_LENGTH", "DUP", "SWAP", "ARITHMETIC",
          "TYPE_CONVERSION", "TYPE_CHECK" -> null;
      case "ARRAY_WRITE" -> operands.size() >= 3
          ? "GoldClientRuntime.arraySet("
              + normalizeExpression(operands.get(0)) + ", "
              + normalizeExpression(operands.get(1)) + ", "
              + normalizeExpression(operands.get(2)) + ");"
          : null;
      case "LOCAL_WRITE" -> translateLocalWrite(operands, context);
      case "FIELD_WRITE" -> translateFieldWrite(operands);
      case "METHOD_CALL" -> translateMethodCall(operands);
      case "CONDITIONAL_BRANCH", "JUMP", "TABLE_SWITCH", "LOOKUP_SWITCH" -> null;
      case "RETURN" -> operands.isEmpty()
          ? "return;"
          : "return " + normalizeExpression(operands.get(0)) + ";";
      case "THROW" -> operands.isEmpty()
          ? null
          : "throw " + normalizeExpression(operands.get(0)) + ";";
      default -> null;
    };
  }

  private static boolean isExpressionOnly(IrOperationModel operation) {
    return switch (operation.kind()) {
      case "CONSTANT", "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE", "CALL_VALUE",
          "ARRAY_READ", "ARRAY_LENGTH", "DUP", "SWAP", "STACK_OPERATION", "ARITHMETIC",
          "TYPE_CONVERSION", "TYPE_CHECK", "DROP" -> true;
      default -> false;
    };
  }

  private static String translateDrop(List<String> operands) {
    if (operands.isEmpty()) return "";
    String expression = normalizeExpression(operands.get(0));
    // POP discards a value, but Java still evaluates side effects that produced it.
    // Pure constants, local reads, and arithmetic expressions can safely disappear.
    if (expression.contains("(") || expression.startsWith("new ")) return expression + ";";
    return "";
  }

  private static String translateLocalWrite(
      List<String> operands,
      TranslationContext context) {
    if (operands.size() < 2)
      return null;

    String local = operands.get(0);
    String value = normalizeExpression(operands.get(1));
    if (context.declareLocal(local)) {
      if ("null".equals(value))
        return "Object " + local + " = null;";
      return "var " + local + " = " + value + ";";
    }
    return local + " = " + value + ";";
  }

  private static String translateFieldWrite(List<String> operands) {
    if (operands.size() == 2)
      return operands.get(0) + " = " + normalizeExpression(operands.get(1)) + ";";
    if (operands.size() >= 3)
      return operands.get(0) + "." + simpleField(operands.get(1))
          + " = " + normalizeExpression(operands.get(2)) + ";";
    return null;
  }

  private static String translateMethodCall(List<String> operands) {
    if (operands.isEmpty())
      return null;
    return normalizeExpression(operands.get(0)) + ";";
  }

  private static String normalizeCondition(String condition) {
    String trimmed = condition == null ? "" : condition.trim();
    return trimmed.isEmpty() ? "false" : trimmed;
  }

  private static String negateCondition(String condition) {
    String trimmed = normalizeCondition(condition);

    String[] operators = {" == ", " != ", " <= ", " >= ", " < ", " > "};
    String[] inverses = {" != ", " == ", " > ", " < ", " >= ", " <= "};

    for (int i = 0; i < operators.length; i++) {
      int index = trimmed.indexOf(operators[i]);
      if (index >= 0) {
        return trimmed.substring(0, index)
            + inverses[i]
            + trimmed.substring(index + operators[i].length());
      }
    }

    if (trimmed.startsWith("!"))
      return trimmed.substring(1).trim();

    return "!(" + trimmed + ")";
  }

  private static int matchingParenthesis(String source, int open) {
    if (open < 0 || open >= source.length() || source.charAt(open) != '(') return -1;
    int depth = 0;
    char quote = 0;
    boolean escaped = false;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (quote != 0) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == quote) quote = 0;
        continue;
      }
      if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static String normalizeExpression(String expression) {
    if (expression == null)
      return null;

    String trimmed = expression.trim();

    if (isQuoted(trimmed) || isJavaLiteral(trimmed))
      return trimmed;

    if (trimmed.startsWith("new ") && !trimmed.endsWith(")") && !trimmed.endsWith("]"))
      return trimmed + "()";

    if (trimmed.startsWith("new ") && trimmed.endsWith(")")) {
      int open = trimmed.indexOf('(');
      int close = matchingParenthesis(trimmed, open);
      // A chained expression such as
      //   new java.lang.StringBuilder().append(value).toString()
      // also starts with `new ` and ends with `)`. Only treat the text as a
      // constructor call when the first argument list owns the final
      // parenthesis; otherwise the chain would be collapsed into one bogus
      // constructor argument.
      if (open > 0 && close == trimmed.length() - 1) {
        String type = trimmed.substring(4, open);
        String argument = trimmed.substring(open + 1, close);
        if (!argument.isBlank() && !isQuoted(argument) && !isJavaLiteral(argument)
            && argument.indexOf(',') < 0 && !argument.contains("\"")
            && !looksLikeExpression(argument)) {
          return "new " + type + "(\"" + escapeJava(argument) + "\")";
        }
      }
    }

    return trimmed;
  }

  private static String returnType(String descriptor) {
    if (descriptor == null)
      return "void";
    int close = descriptor.indexOf(')');
    if (close < 0 || close + 1 >= descriptor.length())
      return "void";
    return descriptorType(descriptor.substring(close + 1));
  }

  private static String parameterList(String descriptor) {
    if (descriptor == null)
      return "";
    int open = descriptor.indexOf('(');
    int close = descriptor.indexOf(')');
    if (open < 0 || close < open)
      return "";

    StringBuilder result = new StringBuilder();
    int index = open + 1;
    int localSlot = 0;
    while (index < close) {
      int start = index;
      while (index < close && descriptor.charAt(index) == '[')
        index++;
      if (index >= close)
        break;

      char type = descriptor.charAt(index);
      if (type == 'L') {
        int end = descriptor.indexOf(';', index);
        if (end < 0 || end > close)
          break;
        index = end + 1;
      } else {
        index++;
      }

      if (result.length() > 0)
        result.append(", ");
      result.append(descriptorType(descriptor.substring(start, index)))
          .append(" local")
          .append(localSlot);
      char parameterType = descriptor.charAt(start);
      localSlot += (parameterType == 'J' || parameterType == 'D') ? 2 : 1;
    }
    return result.toString();
  }

  private static String descriptorType(String descriptor) {
    if (descriptor == null || descriptor.isEmpty())
      return "Object";

    int arrayDepth = 0;
    while (arrayDepth < descriptor.length() && descriptor.charAt(arrayDepth) == '[')
      arrayDepth++;
    if (arrayDepth > 0)
      return descriptorType(descriptor.substring(arrayDepth)) + "[]".repeat(arrayDepth);

    return switch (descriptor.charAt(0)) {
      case 'V' -> "void";
      case 'Z' -> "boolean";
      case 'B' -> "byte";
      case 'C' -> "char";
      case 'S' -> "short";
      case 'I' -> "int";
      case 'J' -> "long";
      case 'F' -> "float";
      case 'D' -> "double";
      case 'L' -> {
        int end = descriptor.indexOf(';');
        yield end > 1 ? descriptor.substring(1, end).replace('/', '.') : "Object";
      }
      default -> "Object";
    };
  }

  // These are compiled once: normalizeExpression runs for every expression of
  // every method, and Pattern.compile dominated whole-JAR translation time.
  private static final java.util.regex.Pattern LOCAL_NAME =
      java.util.regex.Pattern.compile("local\\d+");
  private static final java.util.regex.Pattern QUALIFIED_NAME =
      java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+");
  private static final java.util.regex.Pattern CALL_EXPRESSION =
      java.util.regex.Pattern.compile(
          "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*\\([^)]*\\)");
  private static final java.util.regex.Pattern BINARY_EXPRESSION =
      java.util.regex.Pattern.compile(
          ".*\\s(?:\\+|-|\\*|/|%|<|>|==|!=|<=|>=|&&|\\|\\||\\?|:)\\s.*");
  private static final java.util.regex.Pattern INTEGER_LITERAL =
      java.util.regex.Pattern.compile("-?(?:0|[1-9]\\d*)(?:[lLfFdD])?");
  private static final java.util.regex.Pattern DECIMAL_LITERAL =
      java.util.regex.Pattern.compile("-?(?:0|[1-9]\\d*)\\.\\d+(?:[fFdD])?");

  private static boolean looksLikeExpression(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty())
      return false;

    return LOCAL_NAME.matcher(trimmed).matches()
        || trimmed.contains("->")
        // Some IR producers already express lambdas in JavaScript arrow syntax.
        // Treat those as expressions, not as bare constructor text to quote.
        || trimmed.contains("=>")
        || QUALIFIED_NAME.matcher(trimmed).matches()
        || CALL_EXPRESSION.matcher(trimmed).matches()
        || trimmed.startsWith("new ")
        || (trimmed.startsWith("(") && trimmed.endsWith(")"))
        || BINARY_EXPRESSION.matcher(trimmed).matches();
  }

  private static boolean isJavaLiteral(String value) {
    if ("null".equals(value) || "true".equals(value) || "false".equals(value))
      return true;

    return INTEGER_LITERAL.matcher(value).matches()
        || DECIMAL_LITERAL.matcher(value).matches();
  }

  private static boolean isQuoted(String value) {
    return value.length() >= 2
        && value.startsWith("\"")
        && value.endsWith("\"");
  }

  private static String escapeJava(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String simpleField(String field) {
    int separator = field.lastIndexOf('.');
    return separator >= 0 ? field.substring(separator + 1) : field;
  }

  private static String indent(int level) {
    return "  ".repeat(Math.max(0, level));
  }

  private record SwitchArm(int key, String label) {}

  private record LoopShape(int branchIndex, int backEdgeIndex, int exitIndex) {}

  private record ConditionalRender(String source, boolean complete, int lastIndex) {}

  private record RenderResult(String source, boolean complete) {}
}
