package proguard.classfile.editor

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import proguard.classfile.ProgramClass
import proguard.classfile.ProgramMethod
import proguard.classfile.instruction.Instruction
import proguard.classfile.instruction.VariableInstruction
import proguard.testutils.ClassPoolBuilder
import proguard.testutils.CodeAttributeFinder
import proguard.testutils.JavaSource
import proguard.testutils.MethodInstructionCollector

class VariableRemapperTest : FreeSpec({

    "VariableRemapper should remap compact load and store instructions" {
        val (programClassPool, _) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Test.java",
                """
                public class Test {
                    public static int remapInt(int value) {
                        int local;
                        if (value > 0) {
                            local = value;
                        } else {
                            local = -value;
                        }
                        return local;
                    }

                    public static Object remapReference(Object value) {
                        Object local;
                        if (value == null) {
                            local = new Object();
                        } else {
                            local = value;
                        }
                        return local;
                    }
                }
                """.trimIndent(),
            ),
            javacArguments = listOf("-source", "1.8", "-target", "1.8"),
        )
        val clazz = programClassPool.getClass("Test") as ProgramClass

        remap(clazz, "remapInt", "(I)I", intArrayOf(4, 5)) shouldContainExactly listOf(
            Instruction.OP_ILOAD to 4,
            Instruction.OP_ILOAD to 4,
            Instruction.OP_ISTORE to 5,
            Instruction.OP_ILOAD to 4,
            Instruction.OP_ISTORE to 5,
            Instruction.OP_ILOAD to 5,
        )
        remap(clazz, "remapReference", "(Ljava/lang/Object;)Ljava/lang/Object;", intArrayOf(4, 5)) shouldContainExactly listOf(
            Instruction.OP_ALOAD to 4,
            Instruction.OP_ASTORE to 5,
            Instruction.OP_ALOAD to 4,
            Instruction.OP_ASTORE to 5,
            Instruction.OP_ALOAD to 5,
        )
    }

    "VariableRemapper should preserve adjacent reference and integer stores when their slots swap" {
        val (programClassPool, _) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Test.java",
                """
                public class Test {
                    public static void remapAdjacent(Object input, int value) {
                        Object local2 = input;
                        Object local3 = input;
                        Object local4 = input;
                        Object local5 = input;
                        Object local6 = input;
                        Object reference = input;
                        int integer = value;
                        if (local2 == null || local3 == null || local4 == null || local5 == null || local6 == null) {
                            throw new IllegalStateException();
                        }
                        System.out.println(reference);
                        System.out.println(integer);
                    }
                }
                """.trimIndent(),
            ),
            javacArguments = listOf("-source", "1.8", "-target", "1.8"),
        )
        val clazz = programClassPool.getClass("Test") as ProgramClass
        val variableMap = IntArray(9) { it }.apply {
            this[7] = 8
            this[8] = 7
        }

        remap(clazz, "remapAdjacent", "(Ljava/lang/Object;I)V", variableMap)
            .filter { (opcode, index) -> index == 7 || index == 8 }
            .shouldContainInOrder(
                Instruction.OP_ASTORE to 8,
                Instruction.OP_ISTORE to 7,
            )
    }
})

private fun remap(
    clazz: ProgramClass,
    name: String,
    descriptor: String,
    variableMap: IntArray,
): List<Pair<Byte, Int>> {
    val method = clazz.findMethod(name, descriptor) as ProgramMethod
    val codeAttribute = CodeAttributeFinder.findCodeAttribute(method)!!
    VariableRemapper().apply { setVariableMap(variableMap) }
        .visitCodeAttribute(clazz, method, codeAttribute)

    return MethodInstructionCollector.getMethodInstructions(clazz, method)
        .filterIsInstance<VariableInstruction>()
        .map { it.opcode to it.variableIndex }
}
