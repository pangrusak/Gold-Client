package com.goldclient.analyzer;

import com.goldclient.model.BasicBlockModel;
import com.goldclient.model.ControlFlowModel;
import com.goldclient.model.InstructionModel;
import com.goldclient.model.MethodModel;
import com.goldclient.model.TryCatchModel;
import java.util.*;

public final class ControlFlowAnalyzer {
  private static final Set<Integer> CONDITIONAL_BRANCHES = Set.of(
      153, 154, 155, 156, 157, 158,
      159, 160, 161, 162, 163, 164, 165, 166,
      198, 199
  );

  private static final Set<Integer> TERMINATORS = Set.of(
      169, 172, 173, 174, 175, 176, 177, 191
  );

  public ControlFlowModel analyze(MethodModel method) {
    List<InstructionModel> instructions = method.instructions();
    if (instructions.isEmpty())
      return new ControlFlowModel(0, List.of());

    Map<String, Integer> labels = findLabels(instructions);
    Set<Integer> starts = new TreeSet<>();
    starts.add(0);

    for (int i = 0; i < instructions.size(); i++) {
      InstructionModel instruction = instructions.get(i);
      if ("LABEL".equals(instruction.opcodeName()))
        starts.add(i);

      if (isBranch(instruction)) {
        for (String target : branchTargets(instruction))
          addLabelTarget(starts, labels, target);
        addNextInstructionStart(starts, i, instructions.size());
      }

      if (isTerminator(instruction))
        addNextInstructionStart(starts, i, instructions.size());
    }

    for (TryCatchModel block : method.tryCatchBlocks()) {
      addLabelTarget(starts, labels, block.startLabel());
      addLabelTarget(starts, labels, block.endLabel());
      addLabelTarget(starts, labels, block.handlerLabel());
    }

    List<Integer> startList = new ArrayList<>(starts);
    List<BasicBlockModel> blocks = new ArrayList<>();
    Map<Integer, Integer> instructionToBlock = new HashMap<>();

    for (int i = 0; i < startList.size(); i++) {
      int start = startList.get(i);
      int end = i + 1 < startList.size() ? startList.get(i + 1) : instructions.size();
      int blockId = blocks.size();

      for (int instructionIndex = start; instructionIndex < end; instructionIndex++)
        instructionToBlock.put(instructionIndex, blockId);

      blocks.add(new BasicBlockModel(blockId, start, end, new ArrayList<>(), new ArrayList<>()));
    }

    List<Set<Integer>> successors = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++)
      successors.add(new LinkedHashSet<>());

    for (int i = 0; i < blocks.size(); i++) {
      BasicBlockModel block = blocks.get(i);
      int last = lastRealInstruction(instructions, block.startInstruction(), block.endInstruction());
      if (last < block.startInstruction())
        continue;

      InstructionModel instruction = instructions.get(last);

      if (isSwitch(instruction) || isUnconditionalBranch(instruction)) {
        for (String target : branchTargets(instruction))
          addSuccessor(successors.get(i), labels, instructionToBlock, target);
      } else if (isConditional(instruction)) {
        for (String target : branchTargets(instruction))
          addSuccessor(successors.get(i), labels, instructionToBlock, target);
        addFallthrough(successors.get(i), i, blocks.size());
      } else if (!isTerminator(instruction)) {
        addFallthrough(successors.get(i), i, blocks.size());
      }

      addExceptionSuccessors(
          successors.get(i), block, method.tryCatchBlocks(),
          labels, instructionToBlock);
    }

    List<Set<Integer>> predecessors = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++)
      predecessors.add(new LinkedHashSet<>());

    for (int from = 0; from < successors.size(); from++)
      for (int to : successors.get(from))
        if (to >= 0 && to < blocks.size())
          predecessors.get(to).add(from);

    List<BasicBlockModel> result = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) {
      BasicBlockModel block = blocks.get(i);
      result.add(new BasicBlockModel(
          block.id(),
          block.startInstruction(),
          block.endInstruction(),
          new ArrayList<>(successors.get(i)),
          new ArrayList<>(predecessors.get(i))
      ));
    }

    return new ControlFlowModel(instructionToBlock.getOrDefault(0, 0), result);
  }

  private static Map<String, Integer> findLabels(List<InstructionModel> instructions) {
    Map<String, Integer> labels = new HashMap<>();
    for (int i = 0; i < instructions.size(); i++) {
      InstructionModel instruction = instructions.get(i);
      if ("LABEL".equals(instruction.opcodeName()) && !instruction.operands().isEmpty())
        labels.put(instruction.operands().get(0), i);
    }
    return labels;
  }

  private static void addLabelTarget(
      Set<Integer> starts, Map<String, Integer> labels, String label) {
    Integer target = labels.get(label);
    if (target != null)
      starts.add(target);
  }

  private static void addNextInstructionStart(Set<Integer> starts, int index, int size) {
    if (index + 1 < size)
      starts.add(index + 1);
  }

  private static int lastRealInstruction(List<InstructionModel> instructions, int start, int end) {
    for (int i = end - 1; i >= start; i--)
      if (!"LABEL".equals(instructions.get(i).opcodeName()))
        return i;
    return -1;
  }

  private static boolean isBranch(InstructionModel instruction) {
    int opcode = instruction.opcode();
    return isConditional(instruction)
        || opcode == 167 || opcode == 168 || opcode == 200 || opcode == 201
        || isSwitch(instruction);
  }

  private static boolean isConditional(InstructionModel instruction) {
    return CONDITIONAL_BRANCHES.contains(instruction.opcode());
  }

  private static boolean isUnconditionalBranch(InstructionModel instruction) {
    int opcode = instruction.opcode();
    return opcode == 167 || opcode == 168 || opcode == 200 || opcode == 201;
  }

  private static boolean isSwitch(InstructionModel instruction) {
    int opcode = instruction.opcode();
    return opcode == 170 || opcode == 171;
  }

  private static boolean isTerminator(InstructionModel instruction) {
    return TERMINATORS.contains(instruction.opcode());
  }

  private static List<String> branchTargets(InstructionModel instruction) {
    List<String> operands = instruction.operands();
    int opcode = instruction.opcode();

    if (isConditional(instruction) || isUnconditionalBranch(instruction))
      return operands.isEmpty() ? List.of() : List.of(operands.get(0));

    if (opcode == 170) {
      if (operands.size() < 3) return List.of();
      return operands.subList(2, operands.size());
    }

    if (opcode == 171) {
      if (operands.isEmpty()) return List.of();
      List<String> targets = new ArrayList<>();
      targets.add(operands.get(0));
      for (int i = 2; i < operands.size(); i += 2)
        if (i + 1 < operands.size())
          targets.add(operands.get(i + 1));
      return targets;
    }

    return List.of();
  }

  private static void addSuccessor(
      Set<Integer> successors,
      Map<String, Integer> labels,
      Map<Integer, Integer> instructionToBlock,
      String label) {
    Integer instruction = labels.get(label);
    if (instruction == null) return;
    Integer block = instructionToBlock.get(instruction);
    if (block != null) successors.add(block);
  }

  private static void addFallthrough(Set<Integer> successors, int block, int blockCount) {
    if (block + 1 < blockCount)
      successors.add(block + 1);
  }

  private static void addExceptionSuccessors(
      Set<Integer> successors,
      BasicBlockModel block,
      List<TryCatchModel> tryCatchBlocks,
      Map<String, Integer> labels,
      Map<Integer, Integer> instructionToBlock) {
    for (TryCatchModel handler : tryCatchBlocks) {
      Integer start = labels.get(handler.startLabel());
      Integer end = labels.get(handler.endLabel());
      Integer target = labels.get(handler.handlerLabel());
      if (start == null || end == null || target == null) continue;

      int blockStart = block.startInstruction();
      int blockEnd = Math.max(blockStart, block.endInstruction() - 1);
      boolean overlaps = blockStart < end && blockEnd >= start;
      if (!overlaps) continue;

      Integer handlerBlock = instructionToBlock.get(target);
      if (handlerBlock != null)
        successors.add(handlerBlock);
    }
  }
}
