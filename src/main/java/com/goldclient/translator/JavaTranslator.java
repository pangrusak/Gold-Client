package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
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

    Map<String, Integer> labels = findLabels(operations, 0, operations.size());
    RenderResult rendered = renderRange(
        operations, 0, operations.size(), 1, context, labels);
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
        ConditionalRender branchChain = renderConditionalValueChain(
            operations, i, end, indent, context, labels);
        if (branchChain != null) {
          source.append(branchChain.source());
          complete &= branchChain.complete();
          i = branchChain.lastIndex();
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

        if (context.isActiveLoop(labelKey(target))) {
          source.append(indent(indent)).append("continue;\n");
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
   * Structures a conventional JVM exception table entry as a source try/catch.
   * Complex/malformed regions are left explicitly incomplete rather than
   * silently claiming that exception behavior was preserved.
   */
  private static ConditionalRender renderTryCatch(
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
      if (normalCompletionEnd > tryEnd + 1) {
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
    if (defaultIndex == null || defaultIndex <= switchIndex || defaultIndex >= end) return null;
    Map<String, Integer> targetIndices = new HashMap<>();
    for (SwitchArm arm : arms) {
      Integer target = labels.get(labelKey(arm.label()));
      if (target == null || target <= switchIndex || target >= end) return null;
      targetIndices.put(arm.label(), target);
    }

    int lastExplicitTarget = targetIndices.values().stream()
        .mapToInt(Integer::intValue).max().orElse(switchIndex);
    int lastTarget = Math.max(defaultIndex, lastExplicitTarget);

    int joinIndex = end;
    String joinLabel = null;
    boolean defaultIsJoin = false;
    if (defaultIndex > lastExplicitTarget) {
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
      if (targetIndex != null && targetIndex > lastTarget && targetIndex < joinIndex) {
        joinIndex = targetIndex;
        joinLabel = targetLabel;
      }
    }

    Map<Integer, List<String>> labelsAtTarget = new java.util.TreeMap<>();
    for (SwitchArm arm : arms) {
      int target = targetIndices.get(arm.label());
      labelsAtTarget.computeIfAbsent(target, ignored -> new java.util.ArrayList<>())
          .add("case " + arm.key() + ":");
    }
    if (!defaultIsJoin) {
      labelsAtTarget.computeIfAbsent(defaultIndex, ignored -> new java.util.ArrayList<>())
          .add("default:");
    }

    List<Integer> orderedTargets = new java.util.ArrayList<>(labelsAtTarget.keySet());
    orderedTargets.sort(Integer::compareTo);
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("switch (")
        .append(normalizeExpression(value)).append(") {\n");
    boolean complete = true;

    for (int block = 0; block < orderedTargets.size(); block++) {
      int targetIndex = orderedTargets.get(block);
      int segmentEnd = block + 1 < orderedTargets.size()
          ? orderedTargets.get(block + 1) : joinIndex;
      if (segmentEnd < targetIndex + 1) {
        complete = false;
        continue;
      }

      for (String label : labelsAtTarget.get(targetIndex)) {
        source.append(indent(indent + 1)).append(label).append("\n");
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
          operations, targetIndex + 1, bodyEnd, indent + 2, context, labels);
      source.append(body.source());
      complete &= body.complete();

      boolean endsAbruptly = bodyEnd > targetIndex + 1
          && (isTerminal(operations.get(bodyEnd - 1)) || jumpsToJoin);
      boolean fallsThroughToNextCase = block + 1 < orderedTargets.size()
          && segmentEnd == orderedTargets.get(block + 1)
          && !endsAbruptly;
      if (jumpsToJoin || (!fallsThroughToNextCase && !endsAbruptly)) {
        source.append(indent(indent + 2)).append("break;\n");
      }
    }

    source.append(indent(indent)).append("}\n");
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

    List<Integer> falseBranches = new java.util.ArrayList<>();
    Integer falseLabelIndex = null;
    for (int i = branchIndex + 1; i < trueLabelIndex; i++) {
      IrOperationModel candidate = operations.get(i);
      if ("JUMP".equals(candidate.kind())) return null;
      if ("CONDITIONAL_BRANCH".equals(candidate.kind())) {
        if (candidate.operands().size() < 2) return null;
        Integer candidateFalseLabel = labels.get(labelKey(candidate.operands().get(1)));
        if (candidateFalseLabel == null) return null;
        if (falseLabelIndex == null) falseLabelIndex = candidateFalseLabel;
        else if (!falseLabelIndex.equals(candidateFalseLabel)) return null;
        falseBranches.add(i);
      }
    }
    if (falseBranches.isEmpty() || falseLabelIndex == null
        || falseLabelIndex <= trueLabelIndex || falseLabelIndex >= end) return null;

    int trueConstantIndex = nextExecutableIndex(operations, trueLabelIndex + 1, end);
    if (trueConstantIndex < 0 || !"CONSTANT".equals(operations.get(trueConstantIndex).kind())
        || !isTrueLikeConstant(operations.get(trueConstantIndex))) return null;
    int trueJumpIndex = nextExecutableIndex(operations, trueConstantIndex + 1, end);
    if (trueJumpIndex < 0 || !"JUMP".equals(operations.get(trueJumpIndex).kind())
        || operations.get(trueJumpIndex).operands().isEmpty()) return null;
    String joinLabel = operations.get(trueJumpIndex).operands().get(0);
    Integer joinIndex = labels.get(labelKey(joinLabel));
    if (joinIndex == null || joinIndex <= falseLabelIndex || joinIndex >= end) return null;

    int falseConstantIndex = nextExecutableIndex(operations, falseLabelIndex + 1, end);
    if (falseConstantIndex < 0 || !"CONSTANT".equals(operations.get(falseConstantIndex).kind())
        || !isFalseLikeConstant(operations.get(falseConstantIndex))) return null;
    for (int i = falseConstantIndex + 1; i < joinIndex; i++) {
      if (!isLoopExitMetadata(operations.get(i))) return null;
    }
    int sinkIndex = nextExecutableIndex(operations, joinIndex + 1, end);
    if (sinkIndex < 0 || operations.get(sinkIndex).operands().isEmpty()
        || !"<unknown>".equals(operations.get(sinkIndex).operands().get(
            operations.get(sinkIndex).operands().size() - 1))) return null;

    String trueValue = operations.get(trueConstantIndex).operands().get(0);
    String falseValue = operations.get(falseConstantIndex).operands().get(0);
    StringBuilder source = new StringBuilder();
    boolean complete = true;
    if ("RETURN".equals(operations.get(sinkIndex).kind())) {
      source.append(indent(indent)).append("if (")
          .append(normalizeCondition(firstBranch.operands().get(0))).append(") {\n")
          .append(indent(indent + 1)).append("return ").append(trueValue).append(";\n")
          .append(indent(indent)).append("}\n");
      int previous = branchIndex;
      for (int falseBranch : falseBranches) {
        RenderResult prefix = renderRange(
            operations, previous + 1, falseBranch, indent, context, labels);
        source.append(prefix.source());
        complete &= prefix.complete();
        source.append(indent(indent)).append("if (")
            .append(normalizeCondition(operations.get(falseBranch).operands().get(0)))
            .append(") {\n")
            .append(indent(indent + 1)).append("return ").append(falseValue).append(";\n")
            .append(indent(indent)).append("}\n");
        previous = falseBranch;
      }
      source.append(indent(indent)).append("return ").append(trueValue).append(";\n");
      return new ConditionalRender(source.toString(), complete, sinkIndex);
    }
    if (!"LOCAL_WRITE".equals(operations.get(sinkIndex).kind())
        || operations.get(sinkIndex).operands().size() < 2 || falseBranches.size() != 1) return null;

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
    if (targetIndex == null || targetIndex <= branchIndex || targetIndex >= end) return null;
    // A try-region start is not an ordinary early-return destination: keep its
    // markers available so renderTryCatch can preserve the protected extent.
    if (findTryBeginBeforeTarget(
        operations, branchIndex + 1, targetIndex, branch.operands().get(1), labels) >= 0) {
      return null;
    }

    int returnIndex = -1;
    for (int i = targetIndex + 1; i < end; i++) {
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

    int fallthroughEnd = targetIndex;
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
    RenderResult fallthrough = renderRange(
        operations, branchIndex + 1, fallthroughEnd, indent, context, labels);
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("if (")
        .append(normalizeCondition(branch.operands().get(0))).append(") {\n");
    if (returnOperation.operands().isEmpty()) {
      source.append(indent(indent + 1)).append("return;\n");
    } else {
      source.append(indent(indent + 1)).append("return ")
          .append(normalizeExpression(returnOperation.operands().get(0))).append(";\n");
    }
    source.append(indent(indent)).append("}\n").append(fallthrough.source());
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
        && "<unknown>".equals(sink.operands().get(1))) {
      String local = sink.operands().get(0);
      String assignment = context.declareLocal(local) ? "var " + local : local;
      source.append(indent(indent)).append(assignment).append(" = ").append(ternary).append(";\n");
      return new ConditionalRender(source.toString(), true, sinkIndex);
    }
    if (!List.of("FIELD_WRITE", "METHOD_CALL", "RETURN").contains(sink.kind())) return null;
    List<String> replacedOperands = sink.operands().stream()
        .map(value -> value.replace("<unknown>", ternary)).toList();
    IrOperationModel replaced = new IrOperationModel(
        sink.kind(), replacedOperands, sink.sourceInstruction());
    String lowered = translateOperation(replaced, context);
    if (lowered == null || lowered.isBlank()) return null;
    source.append(indent(indent)).append(lowered).append("\n");
    return new ConditionalRender(source.toString(), true, sinkIndex);
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
          || "STACK_OPERATION".equals(operation.kind()) || "DUP".equals(operation.kind())
          || "SWAP".equals(operation.kind())) return null;
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

  private static ConditionalRender renderConditional(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    String condition = branch.operands().get(0);
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
          context.enterLoop(normalizedLoopTarget);
          RenderResult body;
          try {
            body = renderRange(
                operations, branchIndex + 1, backwardJump, indent + 1, context, labels);
          } finally {
            context.exitLoop();
          }
          StringBuilder source = new StringBuilder();
          source.append(indent(indent)).append("while (")
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
      if (!jump.operands().isEmpty()) {
        Integer endIndex = labels.get(labelKey(jump.operands().get(0)));
        if (endIndex != null && endIndex > targetIndex) {
          // A nested if may join at a label outside the current render range
          // (for example, both arms exit to an enclosing if/loop join). Render
          // only the portion owned by this range, leaving the enclosing renderer
          // to consume the shared continuation.
          int structuredEnd = Math.min(endIndex, end);
          RenderResult fallthroughBody = renderRange(
              operations, branchIndex + 1, jumpIndex, indent + 1, context, labels);
          RenderResult targetBody = renderRange(
              operations, targetIndex + 1, structuredEnd, indent + 1, context, labels);

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
              endIndex - 1);
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

    int lastIndex = fallthroughEnd < targetIndex ? fallthroughEnd - 1 : targetIndex;
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
    context.enterLoop(loopLabel);
    RenderResult body;
    try {
      body = renderRange(
          operations, targetIndex + 1, branchIndex, indent + 1, context, labels);
    } finally {
      context.exitLoop();
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent))
        .append("do {\n");
    source.append(body.source());
    source.append(indent(indent))
        .append("} while (")
        .append(normalizeCondition(condition))
        .append(");\n");

    return new ConditionalRender(source.toString(), body.complete(), branchIndex);
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

    for (int branchIndex = loopHeaderIndex + 1; branchIndex < end; branchIndex++) {
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
      if ("JUMP".equals(backEdge.kind()) && !backEdge.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(backEdge.operands().get(0)))) {
        return new LoopShape(branchIndex, backEdgeIndex, exitIndex);
      }
    }
    return null;
  }

  private static boolean isLoopExitMetadata(IrOperationModel operation) {
    return "LABEL".equals(operation.kind()) || "TRY_BEGIN".equals(operation.kind())
        || "TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind())
        || "STACK_OPERATION".equals(operation.kind());
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
    context.enterLoop(loopLabel);
    RenderResult body;
    try {
      body = renderRange(
          operations, loopHeaderIndex + 1, loop.backEdgeIndex(), indent + 1, context, labels);
    } finally {
      context.exitLoop();
    }
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("while (true) {\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    return new ConditionalRender(source.toString(), body.complete(), loop.backEdgeIndex());
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
    String condition = operations.get(loop.branchIndex()).operands().get(0);
    context.enterLoop(loopLabel);
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
      context.exitLoop();
    }

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("while (true) {\n")
        .append(conditionPrelude.source())
        .append(indent(indent + 1)).append("if (")
        .append(normalizeCondition(condition)).append(") break;\n")
        .append(body.source())
        .append(indent(indent)).append("}\n");
    int lastIndex = loop.exitIndex() < end ? loop.exitIndex() : end - 1;
    return new ConditionalRender(
        source.toString(),
        conditionPrelude.complete() && body.complete(),
        lastIndex);
  }

  private static int findBackwardConditional(
      List<IrOperationModel> operations,
      int labelIndex,
      int end,
      String label) {
    for (int i = labelIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2
          && labelKey(label).equals(labelKey(operation.operands().get(1))))
        return i;
    }
    return -1;
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
      if ("JUMP".equals(backEdge.kind()) && !backEdge.operands().isEmpty()
          && labelKey(loopLabel).equals(labelKey(backEdge.operands().get(0)))) {
        return i;
      }
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

  private static String normalizeExpression(String expression) {
    if (expression == null)
      return null;

    String trimmed = expression.trim();

    if (isQuoted(trimmed) || isJavaLiteral(trimmed))
      return trimmed;

    if (trimmed.startsWith("new ") && !trimmed.endsWith(")"))
      return trimmed + "()";

    if (trimmed.startsWith("new ") && trimmed.endsWith(")")) {
      int open = trimmed.indexOf('(');
      if (open > 0 && open < trimmed.length() - 1) {
        String type = trimmed.substring(4, open);
        String argument = trimmed.substring(open + 1, trimmed.length() - 1);
        if (!argument.isBlank() && !isQuoted(argument) && !isJavaLiteral(argument)
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

  private static boolean looksLikeExpression(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty())
      return false;

    return trimmed.matches("local\\d+")
        || trimmed.contains("->")
        // Some IR producers already express lambdas in JavaScript arrow syntax.
        // Treat those as expressions, not as bare constructor text to quote.
        || trimmed.contains("=>")
        || trimmed.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")
        || trimmed.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*\\([^)]*\\)")
        || trimmed.startsWith("new ")
        || (trimmed.startsWith("(") && trimmed.endsWith(")"))
        || trimmed.matches(".*\\s(?:\\+|-|\\*|/|%|<|>|==|!=|<=|>=|&&|\\|\\||\\?|:)\\s.*");
  }

  private static boolean isJavaLiteral(String value) {
    if ("null".equals(value) || "true".equals(value) || "false".equals(value))
      return true;

    return value.matches("-?(?:0|[1-9]\\d*)(?:[lLfFdD])?")
        || value.matches("-?(?:0|[1-9]\\d*)\\.\\d+(?:[fFdD])?");
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
