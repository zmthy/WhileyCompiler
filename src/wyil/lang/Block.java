// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
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

package wyil.lang;

import java.util.*;

import wyil.util.*;

/**
 * <p>
 * A Block is the foundation of the Whiley Intermediate Language. A Block
 * represents a complete sequence of bytecode instructions. For example, every
 * method body is a single Block. Likewise, the constraint for a give type is a
 * Block. Finally, a Block permits attributes to be attached to every bytecode
 * in the block. An example attribute is one for holding the location of the
 * source code which generated the bytecode.
 * </p>
 * 
 * <p>
 * Every Block has a number of dedicate input variables which may or may not be
 * named. In addition, a Block may used an arbitrary number of additional
 * temporary variables. Each variable is allocated to a slot number, starting
 * from zero. Slot zero is reserved for the special variable "$". Likewise, slot
 * one is reserved for the special variable this for blocks which require it.
 * For example, the body of a normal method requires a receiver, whilst
 * functions or headless methods don't.
 * </p>
 * 
 * <p>
 * The main operations on a block are <i>append</i> and <i>import</i>. The
 * former is used in the process of constructing a block. In such case,
 * bytecodes are appended on to the block assuming an identical slot allocation.
 * However, when importing one block into another we cannot assume that the slot
 * allocations are the same. For example, the block representing a constraint on
 * some type might have a single input mapped to slot zero, and a temporary
 * mapped to slot one. When this block is imported into the pre-condition of
 * some function, a collision would occur if e.g. that function has multiple
 * parameters. This is because the second parameter would be mapped to the same
 * register as the temporary in the constraint. We have to <i>shift</i> the slot
 * number of that temporary variable up in order to avoid this collision.
 * </p>
 * 
 * @author djp
 * 
 */
public final class Block implements Iterable<Block.Entry> {
	private final ArrayList<Entry> stmts;	
	
	public Block() {
		this.stmts = new ArrayList<Entry>();
	}
	
	public Block(Collection<Entry> stmts) {
		this.stmts = new ArrayList<Entry>();
		for(Entry s : stmts) {
			add(s.code,s.attributes());
		}
	}

	// ===================================================================
	// Accessor Methods
	// ===================================================================

	public int size() {
		return stmts.size();
	}

	/**
	 * Determine the number of slots used in this block.
	 * 
	 * @return
	 */
	public int numSlots() {		
		HashSet<Integer> slots = new HashSet<Integer>();
		for(Entry s : stmts) {
			s.code.slots(slots);
		}
		int r = 0;
		for(int i : slots) {
			r = Math.max(r,i+1);
		}		
		return r;
	}
		
	public Entry get(int index) {
		return stmts.get(index);
	}

	public Iterator<Entry> iterator() {
		return stmts.iterator();
	}		
	
	// ===================================================================
	// Import Methods
	// ===================================================================

	/**
	 * Shift every slot in this block by amount.
	 * 
	 * @param amount
	 * @return
	 */
	public Block shift(int amount) {
		Block nblock = new Block();
		for(Entry s : stmts) {
			Code ncode = s.code.shift(amount);
			nblock.add(ncode,s.attributes());
		}
		return nblock;
	}
	
	/**
	 * Shift every slot in this block by amount.
	 * 
	 * @param amount
	 * @return
	 */
	public Block relabel() {		
		HashMap<String,String> labels = new HashMap<String,String>();
		for(Entry s : stmts) {
			if(s.code instanceof Code.Label) {
				Code.Label l = (Code.Label) s.code;
				labels.put(l.label, freshLabel());
			}
		}
		Block nblock = new Block();
		for(Entry s : stmts) {
			Code ncode = s.code.relabel(labels);
			nblock.add(ncode,s.attributes());
		}
		return nblock;
	}
	
	// ===================================================================
	// Append Methods
	// ===================================================================
	
	public void add(Block.Entry entry) {
		stmts.add(new Entry(entry.code,entry.attributes()));
	}
	
	public void add(Code c, Attribute... attributes) {
		stmts.add(new Entry(c,attributes));
	}
	
	public void add(Code c, Collection<Attribute> attributes) {
		stmts.add(new Entry(c,attributes));		
	}
	
	public void add(int idx, Code c, Collection<Attribute> attributes) {
		stmts.add(idx,new Entry(c,attributes));
	}
	
	public void addAll(Collection<Entry> stmts) {
		for(Entry s : stmts) {
			add(s.code,s.attributes());
		}
	}
	
	public void addAll(Block stmts) {
		for(Entry s : stmts) {
			add(s.code,s.attributes());
		}
	}
	
	public void addAll(int idx, Block stmts) {
		for(Entry s : stmts) {
			add(idx++, s.code,s.attributes());
		}
	}

	// ===================================================================
	// Replace and Remove Methods
	// ===================================================================
	
	public void set(int index, Code code, Attribute... attributes) {
		stmts.set(index,new Entry(code,attributes));
	}
	
	public void set(int index, Code code, Collection<Attribute> attributes) {
		stmts.set(index, new Entry(code, attributes));
	}
	
	public void remove(int index) {
		stmts.remove(index);
	}

	// ===================================================================
	// Miscellaneous
	// ===================================================================
	
	public String toString() {
		String r = "[";
		
		boolean firstTime=true;
		for(Entry s : stmts) {
			if(!firstTime) {
				r += ", ";
			}
			firstTime=false;
			r += s.toString();
		}
		
		return r + "]";
	}

	private static int _idx=0;
	public static String freshLabel() {
		return "blklab" + _idx++;
	}

	/**
	 * An Entry object represents a bytecode and those attributes currently
	 * associated with it (if any).
	 * 
	 * @author djp
	 * 
	 */
	public static final class Entry extends SyntacticElement.Impl {
		public final Code code;
		
		public Entry(Code code, Attribute... attributes) {
			super(attributes);
			this.code = code;
		}
		
		public Entry(Code code, Collection<Attribute> attributes) {
			super(attributes);
			this.code = code;
		}
				
		public String toString() {
			String r = code.toString();
			if(attributes().size() > 0) {
				r += " # ";
				boolean firstTime=true;
				for(Attribute a : attributes()) {
					if(!firstTime) {
						r += ", ";
					}
					firstTime=false;
					r += a;
				}
			}
			return r;
		}
	}		
}
