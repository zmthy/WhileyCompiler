// Copyright (c) 2012, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyil.builders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import wycc.lang.Attribute;
import wycc.lang.SyntaxError;
import wycc.lang.SyntaxError.InternalFailure;
import wycc.util.Pair;
import wycs.core.Value;
import wycs.solver.Solver;
import wycs.syntax.Expr;
import static wycc.lang.SyntaxError.internalFailure;
import static wycs.solver.Solver.*;
import wyautl.core.Automaton;
import wyautl.io.PrettyAutomataWriter;
import wyil.lang.*;

/**
 * <p>
 * Represents a path through the body of a Wyil method or function. A branch
 * accumulates the constraints known to hold through that particular execution
 * path. These constraints can then be checked for satisfiability at various
 * critical points in the function.
 * </p>
 * <p>
 * When verifying a given function or method, the verifier starts with a single
 * branch at the beginning of the method. When split points in the control-flow
 * graph are encountered, branches are accordingly forked off to represent the
 * alternate control-flow path. At control-flow meet points, branches may also
 * be joined back together (although this is not always strictly necessary). A
 * diagrammatic view might be:
 * </p>
 *
 * <pre>
 *  entry
 *   ||
 *   ||
 *   ##\
 *   ||\\
 *   || \\
 *   || ||
 *   || ##\
 *   || ||\\
 *   ||//  \\
 *   ##/   ||
 *   ||    ||
 *   \/    \/
 *   B1    B3
 * </pre>
 * <p>
 * In the above example, we initially start with one branch <code>B1</code>.
 * This is then forked to give branch <code>B2</code> which, in turn, is forked
 * again to give <code>B3</code>. Subsequently, branch <code>B2</code> is joined
 * back with <code>B1</code>. However, <code>B3</code> is never joined and
 * terminates separately.
 * </p>
 * <p>
 * Every branch (except the first) has a <i>parent</i> branch which it was
 * forked from. Given any two branches there is always a <i>Least Common
 * Ancestor (LCA)</i> --- that is, the latest point which is common to both
 * branches. Finding the LCA can be useful, for example, to identify constraints
 * common to both branches.
 * </p>
 *
 * @author David J. Pearce
 *
 */
public class VcBranch {
	/**
	 * The parent branch which this branch was forked from, or <code>null</code>
	 * if it is the initial "master" branch for the function or method in
	 * question.
	 */
	private final VcBranch parent;

	/**
	 * Maintains the current assignment of variables to expressions.
	 */
	private final Expr[] environment;

	/**
	 * Maintains the current assignment of variables to their types.
	 */
	private final Type[] types;

	/**
	 * The stack of currently active scopes (e.g. for-loop). When the branch
	 * exits a scope, an exit scope event is generated in order that additional
	 * effects make be applied.
	 */
	public final ArrayList<Scope> scopes;

	/**
	 * The block of Wyil bytecode instructions which this branch is traversing
	 * (note: <code>parent == null || block == parent.block</code> must hold).
	 */
	private final Code.Block block;

	/**
	 * The origin determines the bytecode offset in block where this branch was
	 * forked from. For the master branch, this will be <code>0</code>.
	 */
	private final int origin;

	/**
	 * The bytecode index into the above block that this branch is currently at.
	 */
	private int pc;

	/**
	 * Construct the master verification branch for a given code block. The
	 * master for a block has an origin <code>0</code> and an (initial) PC of
	 * <code>0</code> (i.e. the branch begins at the entry of the block).
	 *
	 * @param automaton
	 *            --- the automaton to which constraints generated for this
	 *            block are stored.
	 * @param block
	 *            --- the block of code on which this branch is operating.
	 */
	public VcBranch(Code.Block block) {
		this.parent = null;
		this.block = block;
		this.environment = new Expr[block.numSlots()];
		this.types = new Type[block.numSlots()];
		this.scopes = new ArrayList<Scope>();
		this.origin = 0;
		this.pc = 0;
		scopes.add(new Scope(block.size(), Collections.EMPTY_LIST));
	}

	/**
	 * Construct the master verification branch for a given code block. The
	 * master for a block has an origin <code>0</code> and an (initial) PC of
	 * <code>0</code> (i.e. the branch begins at the entry of the block).
	 *
	 * @param automaton
	 *            --- the automaton to which constraints generated for this
	 *            block are stored.
	 * @param block
	 *            --- the block of code on which this branch is operating.
	 */
	public VcBranch(WyilFile.FunctionOrMethodDeclaration decl, Code.Block block) {
		this.parent = null;
		this.environment = new Expr[block.numSlots()];
		this.types = new Type[block.numSlots()];
		this.scopes = new ArrayList<Scope>();
		this.block = block;
		this.origin = 0;
		this.pc = 0;
		scopes.add(new EntryScope(decl, block.size(), Collections.EMPTY_LIST));
		ArrayList<Type> paramTypes = decl.type().params();
	}

	/**
	 * Private constructor used for forking a child-branch from a parent branch.
	 *
	 * @param parent
	 *            --- parent branch being forked from.
	 */
	private VcBranch(VcBranch parent) {
		this.parent = parent;
		this.environment = parent.environment.clone();
		this.types = Arrays
				.copyOf(parent.types, environment.length);
		this.scopes = new ArrayList<Scope>();
		this.block = parent.block;
		this.origin = parent.pc;
		this.pc = parent.pc;
		for(Scope scope : parent.scopes) {
			this.scopes.add(scope.clone());
		}
	}

	/**
	 * Return the current Program Counter (PC) value for this branch. This must
	 * be a valid index into the code block this branch is operating over.
	 *
	 * @return
	 */
	public int pc() {
		return pc;
	}

	/**
	 * Get the block entry at the current PC position.
	 *
	 * @return
	 */
	public Code.Block.Entry entry() {
		return block.get(pc);
	}

	/**
	 * Get the constraint variable which corresponds to the given Wyil bytecode
	 * register at this point on this branch.
	 *
	 * @param register
	 * @return
	 */
	public Expr read(int register) {
		return environment[register];
	}

	/**
	 * Get the type of a given register at this point in the block.
	 *
	 * @return
	 */
	public Type typeOf(String var) {
		// FIXME: this is such an *horrific* hack, I can't believe I'm doing it.
		// But, it does work most of the time:(
		String[] split = var.split("_");
		int register = Integer.parseInt(split[0].substring(1));
		return types[register];
	}

	/**
	 * Get the type of a given register at this point in the block.
	 *
	 * @return
	 */
	public Type typeOf(int register) {
		return types[register];
	}

	/**
	 * Assign a given expression stored in the automaton to a given Wyil
	 * bytecode register.
	 *
	 * @param register
	 * @param expr
	 */
	public void write(int register, Expr expr, Type type) {
		environment[register] = expr;
		types[register] = type;
	}

	public int nScopes() {
		return scopes.size();
	}

	public Scope scope(int i) {
		return scopes.get(i);
	}

	/**
	 * Get the first scope matching a given scope kind.
	 *
	 * @param clazz
	 * @return
	 */
	public <T extends Scope> T topScope(Class<T> clazz) {
		for(int i=scopes.size();i>0;) {
			i=i-1;
			Scope scope = scopes.get(i);
			if(clazz.isInstance(scope)) {
				return (T) scope;
			}
		}
		return null;
	}

	/**
	 * Terminate the current flow for a given register and begin a new one. In
	 * terms of static-single assignment, this means simply change the index of
	 * the register in question.
	 *
	 * @param register
	 *            Register number to invalidate
	 * @param type
	 *            Type of register being invalidated
	 */
	public Expr.Variable invalidate(int register, Type type) {
		// to invalidate a variable, we assign it a "skolem" constant. That is,
		// a fresh variable which has not been previously encountered in the
		// branch.
		Expr.Variable var = new Expr.Variable("r" + Integer.toString(register) + "_" + pc);
		environment[register] = var;
		types[register] = type;
		return var;
	}

	/**
	 * Invalidate all registers from <code>start</code> upto (but not including)
	 * <code>end</code>.
	 *
	 * @param start
	 *            --- first register to invalidate.
	 * @param end
	 *            --- first register not to invalidate.
	 */
	public void invalidate(int start, int end, Type type) {
		for (int i = start; i != end; ++i) {
			invalidate(i,type);
		}
	}

	/**
	 * Return a reference into the automaton which represents all of the
	 * constraints that hold at this position in the branch.
	 *
	 * @return
	 */
	public Expr constraints() {
		ArrayList<Expr> constraints = new ArrayList<Expr>();
		for (int i = 0; i != scopes.size(); ++i) {
			Scope scope = scopes.get(i);
			constraints.addAll(scope.constraints);
		}
		return And(constraints);
	}

	/**
	 * Add a given constraint to the list of constraints which are assumed to
	 * hold at this point.
	 *
	 * @param constraint
	 */
	public void add(Expr constraint) {
		topScope().constraints.add(constraint);
	}

	/**
	 * Add a given list of constraints to the list of constraints which are assumed to
	 * hold at this point.
	 *
	 * @param constraints
	 */
	public void addAll(List<Expr> constraints) {
		topScope().constraints.addAll(constraints);
	}

	/**
	 * Transform this branch into a list of constraints representing that which
	 * is known to hold at the end of the branch. The generated constraint will
	 * only be in terms of the given parameters and return value for the block.
	 *
	 * @param transformer
	 *            --- responsible for transformining individual bytecodes into
	 *            constraints capturing their semantics.
	 * @return
	 */
	public Expr transform(VcTransformer transformer) {
		ArrayList<VcBranch> children = new ArrayList<VcBranch>();
		int blockSize = block.size();
		while (pc < blockSize) {

			// first, check whether we're departing a scope or not.
			int top = scopes.size() - 1;
			while (top >= 0 && scopes.get(top).end < pc) {
				// yes, we're leaving a scope ... so notify transformer.
				Scope topScope = scopes.get(top);
				scopes.remove(top);
				dispatchExit(topScope, transformer);
				top = top - 1;
			}

			// second, continue to transform the given bytecode
			Code.Block.Entry entry = block.get(pc);
			Code code = entry.code;
			if(code instanceof Codes.Goto) {
				goTo(((Codes.Goto) code).target);
			} else if(code instanceof Codes.If) {
				Codes.If ifc = (Codes.If) code;
				VcBranch trueBranch = fork();
				transformer.transform(ifc,this,trueBranch);
				trueBranch.goTo(ifc.target);
				children.add(trueBranch);
			} else if(code instanceof Codes.Switch) {
				Codes.Switch sw = (Codes.Switch) code;
				VcBranch[] cases = new VcBranch[sw.branches.size()];
				for(int i=0;i!=cases.length;++i) {
					cases[i] = fork();
					children.add(cases[i]);
				}
				transformer.transform(sw,this,cases);
				for(int i=0;i!=cases.length;++i) {
					cases[i].goTo(sw.branches.get(i).second());
				}
				goTo(sw.defaultTarget);
			} else if(code instanceof Codes.IfIs) {
				Codes.IfIs ifs = (Codes.IfIs) code;
				Type type = typeOf(ifs.operand);
				// First, determine the true test
				Type trueType = Type.intersect(type,ifs.rightOperand);
				Type falseType = Type.intersect(type,Type.Negation(ifs.rightOperand));

				if(trueType.equals(Type.T_VOID)) {
					// This indicate that the true branch is unreachable and
					// should not be explored. Observe that this does not mean
					// the true branch is dead-code. Rather, since we're
					// preforming a path-sensitive traversal it means we've
					// uncovered an unreachable path. In this case, this branch
					// remains as the false branch.
					this.write(ifs.operand, read(ifs.operand), falseType);
				} else if(falseType.equals(Type.T_VOID)) {
					// This indicate that the false branch is unreachable (ditto
					// as for true branch). In this case, this branch becomes
					// the true branch.
					goTo(ifs.target);
					this.write(ifs.operand, read(ifs.operand), trueType);
				} else {
					VcBranch trueBranch = fork();
					trueBranch.goTo(ifs.target);
					this.write(ifs.operand, read(ifs.operand), falseType);
					trueBranch.write(ifs.operand, trueBranch.read(ifs.operand), trueType);
					children.add(trueBranch);
				}
			} else if(code instanceof Codes.ForAll) {
				Codes.ForAll fall = (Codes.ForAll) code;
				// FIXME: where should this go?
				for (int i : fall.modifiedOperands) {
					invalidate(i,types[i]);
				}
				Expr.Variable var = invalidate(fall.indexOperand,fall.type.element());

				scopes.add(new ForScope(fall, findLabelIndex(fall.target),
						Collections.EMPTY_LIST, read(fall.sourceOperand),
						var));
				transformer.transform(fall, this);
			} else if(code instanceof Codes.Loop) {
				Codes.Loop loop = (Codes.Loop) code;
				// FIXME: where should this go?
				for (int i : loop.modifiedOperands) {
					invalidate(i,types[i]);
				}

				scopes.add(new LoopScope(loop, findLabelIndex(loop.target),
						Collections.EMPTY_LIST));

				transformer.transform(loop, this);
			} else if(code instanceof Codes.LoopEnd) {
				top = scopes.size() - 1;
				LoopScope ls = (LoopScope) scopes.get(top);
				scopes.remove(top);
				if(ls instanceof ForScope) {
					ForScope fs = (ForScope) ls;
					transformer.end(fs,this);
				} else {
					// normal loop, so the branch ends here
					transformer.end(ls,this);
					break;
				}
			} else if(code instanceof Codes.TryCatch) {
				Codes.TryCatch tc = (Codes.TryCatch) code;
				scopes.add(new TryScope(findLabelIndex(tc.target),
						Collections.EMPTY_LIST));
				transformer.transform(tc, this);
			} else if(code instanceof Codes.AssertOrAssume) {
				Codes.AssertOrAssume ac = (Codes.AssertOrAssume) code;
				boolean isAssertion = code instanceof Codes.Assert;
				scopes.add(new AssertOrAssumeScope(isAssertion,
						findLabelIndex(ac.target), Collections.EMPTY_LIST));
				transformer.transform(ac, this);
			} else if(code instanceof Codes.Return) {
				transformer.transform((Codes.Return) code, this);
				kill();
				break; // we're done!!!
			} else if(code instanceof Codes.Throw) {
				transformer.transform((Codes.Throw) code, this);
				break; // we're done!!!
			} else if(code instanceof Codes.Fail) {
				transformer.transform((Codes.Fail) code, this);
				kill();
				break;
			} else {
				dispatch(transformer);
			}

			// move on to next instruction.
			pc = pc + 1;
		}

		// Now, transform child branches!!!
		for(VcBranch child : children) {
			child.transform(transformer);
			join(child);
		}

		return constraints();
	}

	/**
	 * <p>
	 * Fork a child-branch from this branch. The child branch is (initially)
	 * identical in every way to the parent, however the expectation is that
	 * they will diverge.
	 * </p>
	 *
	 * <pre>
	 *    B1
	 *    ||
	 *    ||
	 *    ##    <- origin
	 *    | \
	 *    ||\\
	 *    || \\
	 *    \/  \/
	 *    B1  B2
	 * </pre>
	 * <p>
	 * The origin for the forked branch is the <code>PC</code value at the split
	 * point. Initially, the <code>PC</code> value for the forked branch is
	 * identical to that of the parent, however it is expected that a
	 * <code>goTo</code> will be used immediately after the fork to jump the
	 * child branch to its logical starting point.
	 * </p>
	 * <p>
	 * A new environment is created for the child branch which, initially, is
	 * identical to that of the parent. As assignments to variables are made on
	 * either branch, these environments will move apart.
	 * </p>
	 *
	 * @return --- The child branch which is forked off this branch.
	 */
	private VcBranch fork() {
		return new VcBranch(this);
	}

	/**
	 * <p>
	 * Merge descendant (i.e. a child or child-of-child, etc) branch back into
	 * this branch. The constraints for this branch must now correctly capture
	 * those constraints that hold coming from either branch (i.e. this
	 * represents a meet-point in the control-flow graph).
	 * </p>
	 * <p>
	 * To generate the constraints which hold after the meet, we take the
	 * logical OR of those constraints holding on this branch prior to the meet
	 * and those which hold on the incoming branch. For example, support we
	 * have:
	 * </p>
	 *
	 * <pre>
	 * 	 y$0 != 0    y$0 != 0
	 *   && x$1 < 1  && x$2 >= 1
	 *        ||      ||
	 *         \\    //
	 *          \\  //
	 *           \\//
	 *            ##
	 *   y$0 != 0 &&
	 * ((x$1 < 1 && x$3 == x$1) ||
	 *  (x$2 >= 1 && x$3 == x$2))
	 * </pre>
	 * <p>
	 * Here, we see that <code>y$0 != 0</code> is constant to both branches and
	 * is ommitted from the disjunction. Furthermore, we've added an assignment
	 * <code>x$3 == </code> onto both sides of the disjunction to capture the
	 * flow of variable <code>x</code> from both sides (since it was modified on
	 * at least one of the branches).
	 * </p>
	 * <p>
	 * One challenge is to determine constraints which are constant to both
	 * sides. Eliminating such constraints from the disjunction reduces the
	 * overall work of the constraint solver.
	 * </p>
	 *
	 * @param incoming
	 *            --- The descendant branch which is being merged into this one.
	 */
	private void join(VcBranch incoming) {
		// First, determine new constraint sequence
		ArrayList<Expr> common = new ArrayList<Expr>();
		ArrayList<Expr> lhsConstraints = new ArrayList<Expr>();
		ArrayList<Expr> rhsConstraints = new ArrayList<Expr>();

		splitConstraints(incoming,common,lhsConstraints,rhsConstraints);

		// Finally, put it all together
		Expr l = And(lhsConstraints);
		Expr r = And(rhsConstraints);

		// can now compute the logical OR of both branches
		Expr join = Or(l,r);

		// now, clear our sequential constraints since we can only have one
		// which holds now: namely, the or of the two branches.
		Scope top = topScope();
		top.constraints.clear();
		top.constraints.addAll(common);
		top.constraints.add(join);
	}

	/**
	 * Kill this branch. Namely, it does not proceed any further.
	 */
	public void kill() {
		// Because this branch is unreachable, need to kill it properly [that
		// includes all subscopes as well].
		for(int i=scopes.size();i>0;--i) {
			VcBranch.Scope s = scope(i-1);
			s.constraints.clear();
		}
		topScope().constraints.add(new Expr.Constant(Value.Bool(false)));
	}

	/**
	 * A region of bytecodes which requires special attention when the branch
	 * exits the scope. For example, when a branch exits the body of a for-loop,
	 * we must ensure that the appopriate loop-invariants hold, etc.
	 *
	 * @author David J. Pearce
	 *
	 */
	public static class Scope implements Cloneable {
		public final ArrayList<Expr> constraints;
		public int end;

		public Scope(int end, List<Expr> constraints) {
			this.end = end;
			this.constraints = new ArrayList<Expr>(constraints);
		}

		public Scope clone() {
			return new Scope(end,constraints);
		}
	}

	/**
	 * Represents the scope of a general loop bytecode.
	 *
	 * @author David J. Pearce
	 *
	 * @param <T>
	 */
	public static class LoopScope<T extends Codes.Loop> extends
			VcBranch.Scope {
		public final T loop;

		public LoopScope(T loop, int end, List<Expr> constraints) {
			super(end,constraints);
			this.loop = loop;
		}

		public LoopScope<T> clone() {
			return new LoopScope(loop,end,constraints);
		}
	}

	/**
	 * Represents the scope of an assert or assume bytecode.
	 *
	 * @author David J. Pearce
	 *
	 * @param <T>
	 */
	public static class AssertOrAssumeScope extends
			VcBranch.Scope {
		public final boolean isAssertion;

		public AssertOrAssumeScope(boolean isAssertion, int end, List<Expr> constraints) {
			super(end,constraints);
			this.isAssertion = isAssertion;
		}

		public AssertOrAssumeScope clone() {
			return new AssertOrAssumeScope(isAssertion, end,constraints);
		}
	}

	/**
	 * Represents the scope of a forall bytecode
	 *
	 * @author David J. Pearce
	 *
	 */
	public static class ForScope extends LoopScope<Codes.ForAll> {
		public final Expr source;
		public final Expr.Variable index;

		public ForScope(Codes.ForAll forall, int end, List<Expr> constraints,
				Expr source, Expr.Variable index) {
			super(forall, end, constraints);
			this.index = index;
			this.source = source;
		}

		public ForScope clone() {
			return new ForScope(loop, end, constraints, source, index);
		}
	}

	/**
	 * Represents the scope of a general try-catch handler.
	 *
	 * @author David J. Pearce
	 *
	 * @param <T>
	 */
	public static class TryScope extends
			VcBranch.Scope {

		public TryScope(int end, List<Expr> constraints) {
			super(end,constraints);
		}

		public TryScope clone() {
			return new TryScope(end,constraints);
		}
	}

	/**
	 * Represents the scope of a method or function
	 *
	 * @author David J. Pearce
	 *
	 * @param <T>
	 */
	public static class EntryScope extends VcBranch.Scope {
		public final WyilFile.FunctionOrMethodDeclaration declaration;

		public EntryScope(WyilFile.FunctionOrMethodDeclaration decl, int end,
				List<Expr> constraints) {
			super(end, constraints);
			this.declaration = decl;
		}

		public EntryScope clone() {
			return new EntryScope(declaration, end, constraints);
		}
	}

	/**
	 * Dispatch on the given bytecode to the appropriate method in transformer
	 * for generating an appropriate constraint to capture the bytecodes
	 * semantics.
	 *
	 * @return
	 */
	private void dispatch(VcTransformer transformer) {
		Code code = entry().code;
		try {
			if(code instanceof Codes.BinaryOperator) {
				transformer.transform((Codes.BinaryOperator)code,this);
			} else if(code instanceof Codes.Convert) {
				transformer.transform((Codes.Convert)code,this);
			} else if(code instanceof Codes.Const) {
				transformer.transform((Codes.Const)code,this);
			} else if(code instanceof Codes.Debug) {
				transformer.transform((Codes.Debug)code,this);
			} else if(code instanceof Codes.FieldLoad) {
				transformer.transform((Codes.FieldLoad)code,this);
			} else if(code instanceof Codes.IndirectInvoke) {
				transformer.transform((Codes.IndirectInvoke)code,this);
			} else if(code instanceof Codes.Invoke) {
				transformer.transform((Codes.Invoke)code,this);
			} else if(code instanceof Codes.Invert) {
				transformer.transform((Codes.Invert)code,this);
			} else if(code instanceof Codes.Label) {
				// skip
			} else if(code instanceof Codes.ListOperator) {
				transformer.transform((Codes.ListOperator)code,this);
			} else if(code instanceof Codes.LengthOf) {
				transformer.transform((Codes.LengthOf)code,this);
			} else if(code instanceof Codes.SubList) {
				transformer.transform((Codes.SubList)code,this);
			} else if(code instanceof Codes.IndexOf) {
				transformer.transform((Codes.IndexOf)code,this);
			} else if(code instanceof Codes.Move) {
				transformer.transform((Codes.Move)code,this);
			} else if(code instanceof Codes.Assign) {
				transformer.transform((Codes.Assign)code,this);
			} else if(code instanceof Codes.Update) {
				transformer.transform((Codes.Update)code,this);
			} else if(code instanceof Codes.NewMap) {
				transformer.transform((Codes.NewMap)code,this);
			} else if(code instanceof Codes.NewList) {
				transformer.transform((Codes.NewList)code,this);
			} else if(code instanceof Codes.NewRecord) {
				transformer.transform((Codes.NewRecord)code,this);
			} else if(code instanceof Codes.NewSet) {
				transformer.transform((Codes.NewSet)code,this);
			} else if(code instanceof Codes.NewTuple) {
				transformer.transform((Codes.NewTuple)code,this);
			} else if(code instanceof Codes.UnaryOperator) {
				transformer.transform((Codes.UnaryOperator)code,this);
			} else if(code instanceof Codes.Dereference) {
				transformer.transform((Codes.Dereference)code,this);
			} else if(code instanceof Codes.Nop) {
				transformer.transform((Codes.Nop)code,this);
			} else if(code instanceof Codes.SetOperator) {
				transformer.transform((Codes.SetOperator)code,this);
			} else if(code instanceof Codes.StringOperator) {
				transformer.transform((Codes.StringOperator)code,this);
			} else if(code instanceof Codes.SubString) {
				transformer.transform((Codes.SubString)code,this);
			} else if(code instanceof Codes.NewObject) {
				transformer.transform((Codes.NewObject)code,this);
			} else if(code instanceof Codes.Throw) {
				transformer.transform((Codes.Throw)code,this);
			} else if(code instanceof Codes.TupleLoad) {
				transformer.transform((Codes.TupleLoad)code,this);
			} else {
				internalFailure("unknown: " + code.getClass().getName(),
						transformer.filename(), entry());
			}
		} catch(InternalFailure e) {
			throw e;
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(), transformer.filename(), entry(), e);
		}
	}

	/**
	 * Dispatch exit scope events to the transformer.
	 *
	 * @param scope
	 * @param transformer
	 */
	private void dispatchExit(Scope scope, VcTransformer transformer) {
		if (scope instanceof ForScope) {
			ForScope fs = (ForScope) scope;
			transformer.exit(fs, this);
		} else if (scope instanceof LoopScope) {
			LoopScope ls = (LoopScope) scope;
			transformer.exit(ls, this);
		} else if (scope instanceof TryScope) {
			TryScope ls = (TryScope) scope;
			transformer.exit(ls, this);
		} else {
			AssertOrAssumeScope ls = (AssertOrAssumeScope) scope;
			transformer.exit(ls, this);
		}
	}

	/**
	 * Reposition the Program Counter (PC) for this branch to a given label in
	 * the block.
	 *
	 * @param label
	 *            --- label to look for, which is assumed to occupy an index
	 *            greater than the current PC (this follows the Wyil requirement
	 *            that branches always go forward).
	 */
	private void goTo(String label) {
		pc = findLabelIndex(label);
	}

	/**
	 * Find the bytecode index of a given label. If the label doesn't exist an
	 * exception is thrown.
	 *
	 * @param label
	 * @return
	 */
	private int findLabelIndex(String label) {
		for (int i = pc; i != block.size(); ++i) {
			Code code = block.get(i).code;
			if (code instanceof Codes.Label) {
				Codes.Label l = (Codes.Label) code;
				if (l.label.equals(label)) {
					return i;
				}
			}
		}
		throw new IllegalArgumentException("unknown label --- " + label);
	}

	private Scope topScope() {
		return scopes.get(scopes.size()-1);
	}

	/**
	 * Split the constraints for this branch and the incoming branch into three
	 * sets: those common to both; those unique to this branch; and, those
	 * unique to the incoming branch.
	 *
	 * @param incoming
	 * @param common
	 * @param myRemainder
	 * @param incomingRemainder
	 */
	private void splitConstraints(VcBranch incoming, ArrayList<Expr> common,
			ArrayList<Expr> myRemainder, ArrayList<Expr> incomingRemainder) {
		ArrayList<Expr> constraints = topScope().constraints;
		ArrayList<Expr> incomingConstraints = incoming.topScope().constraints;

		int min = 0;

		while (min < constraints.size() && min < incomingConstraints.size()) {
			Expr is = constraints.get(min);
			Expr js = incomingConstraints.get(min);
			if (is != js) {
				break;
			}
			min = min + 1;
		}

		for(int k=0;k<min;++k) {
			common.add(constraints.get(k));
		}
		for(int i = min;i < constraints.size();++i) {
			myRemainder.add(constraints.get(i));
		}
		for(int j = min;j < incomingConstraints.size();++j) {
			incomingRemainder.add(incomingConstraints.get(j));
		}
	}

	public Expr And(List<Expr> constraints) {
		if(constraints.size() == 0) {
			return new Expr.Constant(Value.Bool(true));
		} else if(constraints.size() == 1) {
			return constraints.get(0);
		} else {
			Expr nconstraints = null;
			for (Expr e : constraints) {
				if(nconstraints == null) {
					nconstraints = e;
				} else {
					nconstraints = new Expr.Binary(Expr.Binary.Op.AND,e,nconstraints,e.attributes());
				}
			}
			return nconstraints;
		}
	}

	public Expr Or(Expr... constraints) {
		if (constraints.length == 0) {
			return new Expr.Constant(Value.Bool(false));
		} else if (constraints.length == 1) {
			return constraints[0];
		} else {
			Expr nconstraints = null;
			for (Expr e : constraints) {
				if (nconstraints == null) {
					nconstraints = e;
				} else {
					nconstraints = new Expr.Binary(Expr.Binary.Op.OR, e,
							nconstraints, e.attributes());
				}
			}
			return nconstraints;
		}
	}
}
