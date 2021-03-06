package jadx.core.dex.visitors;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.FieldReplaceAttr;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.IndexInsnNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.instructions.mods.ConstructorInsn;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.utils.InstructionRemover;
import jadx.core.utils.exceptions.JadxException;

import java.util.List;

public class ClassModifier extends AbstractVisitor {

	@Override
	public boolean visit(ClassNode cls) throws JadxException {
		for (ClassNode inner : cls.getInnerClasses()) {
			visit(inner);
		}
		if (cls.getAccessFlags().isSynthetic()
				&& cls.getFields().isEmpty()
				&& cls.getMethods().isEmpty()) {
			cls.add(AFlag.DONT_GENERATE);
			return false;
		}
		removeSyntheticFields(cls);
		removeSyntheticMethods(cls);
		removeEmptyMethods(cls);

		checkFieldsInit(cls);

		markAnonymousClass(cls);
		return false;
	}

	private void markAnonymousClass(ClassNode cls) {
		if (cls.isAnonymous()) {
			cls.add(AFlag.ANONYMOUS_CLASS);
		}
	}

	private static void removeSyntheticFields(ClassNode cls) {
		if (!cls.getClassInfo().isInner() || cls.getAccessFlags().isStatic()) {
			return;
		}
		// remove fields if it is synthetic and type is a outer class
		for (FieldNode field : cls.getFields()) {
			if (field.getAccessFlags().isSynthetic() && field.getType().isObject()) {
				ClassInfo clsInfo = ClassInfo.fromType(cls.dex(), field.getType());
				ClassNode fieldsCls = cls.dex().resolveClass(clsInfo);
				ClassInfo parentClass = cls.getClassInfo().getParentClass();
				if (fieldsCls != null
						&& parentClass.equals(fieldsCls.getClassInfo())
						&& field.getName().startsWith("this$") /* TODO: don't check name */) {
					int found = 0;
					for (MethodNode mth : cls.getMethods()) {
						if (removeFieldUsageFromConstructor(mth, field, fieldsCls)) {
							found++;
						}
					}
					if (found != 0) {
						FieldInfo replace = FieldInfo.from(cls.dex(), parentClass, "this", parentClass.getType());
						field.addAttr(new FieldReplaceAttr(replace, true));
						field.add(AFlag.DONT_GENERATE);
					}
				}
			}
		}
	}

	private static boolean removeFieldUsageFromConstructor(MethodNode mth, FieldNode field, ClassNode fieldsCls) {
		if (mth.isNoCode() || !mth.getAccessFlags().isConstructor()) {
			return false;
		}
		List<RegisterArg> args = mth.getArguments(false);
		if (args.isEmpty() || mth.contains(AFlag.SKIP_FIRST_ARG)) {
			return false;
		}
		RegisterArg arg = args.get(0);
		if (!arg.getType().equals(fieldsCls.getClassInfo().getType())) {
			return false;
		}
		BlockNode block = mth.getBasicBlocks().get(0);
		List<InsnNode> instructions = block.getInstructions();
		if (instructions.isEmpty()) {
			return false;
		}
		InsnNode insn = instructions.get(0);
		if (insn.getType() != InsnType.IPUT) {
			return false;
		}
		IndexInsnNode putInsn = (IndexInsnNode) insn;
		FieldInfo fieldInfo = (FieldInfo) putInsn.getIndex();
		if (!fieldInfo.equals(field.getFieldInfo()) || !putInsn.getArg(0).equals(arg)) {
			return false;
		}
		mth.removeFirstArgument();
		InstructionRemover.remove(mth, block, insn);
		// other arg usage -> wrap with IGET insn
		if (arg.getSVar().getUseCount() != 0) {
			InsnNode iget = new IndexInsnNode(InsnType.IGET, fieldInfo, 1);
			iget.addArg(insn.getArg(1));
			for (InsnArg insnArg : arg.getSVar().getUseList()) {
				insnArg.wrapInstruction(iget);
			}
		}
		return true;
	}

	private static void removeSyntheticMethods(ClassNode cls) {
		for (MethodNode mth : cls.getMethods()) {
			if (mth.isNoCode()) {
				continue;
			}
			AccessInfo af = mth.getAccessFlags();
			// remove bridge methods
			if (af.isBridge() && af.isSynthetic() && !isMethodUniq(cls, mth)) {
				// TODO add more checks before method deletion
				mth.add(AFlag.DONT_GENERATE);
			}
			// remove synthetic constructor for inner non-static classes
			if (af.isSynthetic() && af.isConstructor() && mth.getBasicBlocks().size() == 2) {
				List<InsnNode> insns = mth.getBasicBlocks().get(0).getInstructions();
				if (insns.size() == 1 && insns.get(0).getType() == InsnType.CONSTRUCTOR) {
					ConstructorInsn constr = (ConstructorInsn) insns.get(0);
					if (constr.isThis() && !mth.getArguments(false).isEmpty()) {
						mth.removeFirstArgument();
						mth.add(AFlag.DONT_GENERATE);
					}
				}
			}
		}
	}

	private static boolean isMethodUniq(ClassNode cls, MethodNode mth) {
		MethodInfo mi = mth.getMethodInfo();
		for (MethodNode otherMth : cls.getMethods()) {
			if (otherMth != mth) {
				MethodInfo omi = otherMth.getMethodInfo();
				if (omi.getName().equals(mi.getName())
						&& omi.getArgumentsTypes().size() == mi.getArgumentsTypes().size()) {
					// TODO: check objects types
					return false;
				}
			}
		}
		return true;
	}

	private static void removeEmptyMethods(ClassNode cls) {
		for (MethodNode mth : cls.getMethods()) {
			AccessInfo af = mth.getAccessFlags();

			// remove public empty constructors
			if (af.isConstructor()
					&& af.isPublic()
					&& mth.getArguments(false).isEmpty()
					&& !mth.contains(AType.JADX_ERROR)) {
				List<BlockNode> bb = mth.getBasicBlocks();
				if (bb == null || bb.isEmpty() || allBlocksEmpty(bb)) {
					mth.add(AFlag.DONT_GENERATE);
				}
			}
		}
	}

	private static boolean allBlocksEmpty(List<BlockNode> blocks) {
		for (BlockNode block : blocks) {
			if (!block.getInstructions().isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static void checkFieldsInit(ClassNode cls) {
		MethodNode clinit = cls.searchMethodByName("<clinit>()V");
		if (clinit == null
				|| !clinit.getAccessFlags().isStatic()
				|| clinit.isNoCode()) {
			return;
		}

		for (BlockNode block : clinit.getBasicBlocks()) {
			for (InsnNode insn : block.getInstructions()) {
				if (insn.getType() == InsnType.SPUT) {
					processStaticFieldAssign(cls, (IndexInsnNode) insn);
				}
			}
		}
	}

	/**
	 * Remove field initialization if it assign in "<clinit>" method
	 */
	private static void processStaticFieldAssign(ClassNode cls, IndexInsnNode insn) {
		FieldInfo field = (FieldInfo) insn.getIndex();
		String thisClass = cls.getClassInfo().getFullName();
		if (field.getDeclClass().getFullName().equals(thisClass)) {
			FieldNode fn = cls.searchField(field);
			if (fn != null && fn.getAccessFlags().isFinal()) {
				fn.remove(AType.FIELD_VALUE);
			}
		}
	}

}
