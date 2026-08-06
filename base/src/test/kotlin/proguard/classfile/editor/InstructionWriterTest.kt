package proguard.classfile.editor

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import proguard.classfile.ProgramClass
import proguard.classfile.ProgramMethod
import proguard.classfile.attribute.CodeAttribute
import proguard.classfile.instruction.Instruction
import proguard.classfile.instruction.VariableInstruction
import proguard.classfile.instruction.visitor.InstructionVisitor
import proguard.testutils.ClassPoolBuilder
import proguard.testutils.CodeAttributeFinder
import proguard.testutils.JavaSource

class InstructionWriterTest : FreeSpec({

    "InstructionWriter should widen compact store instructions without changing their operation" {
        val (programClassPool, _) = ClassPoolBuilder.fromSource(
            JavaSource(
                "Test.java",
                """
                public class Test {
                    public static void storeInt(int value) {
                        int local = value;
                    }

                    public static void storeReference(Object value) {
                        Object local = value;
                    }
                }
                """.trimIndent(),
            ),
            javacArguments = listOf("-source", "1.8", "-target", "1.8"),
        )
        val clazz = programClassPool.getClass("Test") as ProgramClass

        widenCompactStore(clazz, "storeInt", "(I)V", Instruction.OP_ISTORE_1, Instruction.OP_ISTORE)
        widenCompactStore(
            clazz,
            "storeReference",
            "(Ljava/lang/Object;)V",
            Instruction.OP_ASTORE_1,
            Instruction.OP_ASTORE,
        )
    }
})

private fun widenCompactStore(
    clazz: ProgramClass,
    name: String,
    descriptor: String,
    compactOpcode: Byte,
    canonicalOpcode: Byte,
) {
    val method = clazz.findMethod(name, descriptor) as ProgramMethod
    val codeAttribute = CodeAttributeFinder.findCodeAttribute(method)!!
    val (offset, instruction) = compactVariableInstruction(clazz, method, codeAttribute, compactOpcode)

    InstructionWriter().apply {
        reset(codeAttribute.u4codeLength)
        instruction.variableIndex = 4
        visitVariableInstruction(clazz, method, codeAttribute, offset, instruction)
        visitCodeAttribute(clazz, method, codeAttribute)
    }

    val (_, rewrittenInstruction) = compactVariableInstruction(clazz, method, codeAttribute, canonicalOpcode)
    rewrittenInstruction.opcode shouldBe canonicalOpcode
    rewrittenInstruction.variableIndex shouldBe 4
}

private fun compactVariableInstruction(
    clazz: ProgramClass,
    method: ProgramMethod,
    codeAttribute: CodeAttribute,
    opcode: Byte,
): Pair<Int, VariableInstruction> {
    var found: Pair<Int, VariableInstruction>? = null
    codeAttribute.instructionsAccept(clazz, method, object : InstructionVisitor {
        override fun visitAnyInstruction(
            clazz: proguard.classfile.Clazz,
            method: proguard.classfile.Method,
            codeAttribute: CodeAttribute,
            offset: Int,
            instruction: Instruction,
        ) {}

        override fun visitVariableInstruction(
            clazz: proguard.classfile.Clazz,
            method: proguard.classfile.Method,
            codeAttribute: CodeAttribute,
            offset: Int,
            variableInstruction: VariableInstruction,
        ) {
            if (found == null && variableInstruction.opcode == opcode) {
                found = offset to variableInstruction
            }
        }
    })
    return requireNotNull(found)
}
