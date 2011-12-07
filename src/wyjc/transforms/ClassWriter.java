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

package wyjc.transforms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import wyil.ModuleLoader;
import wyil.Transform;
import wyil.lang.Module;
import wyjc.io.ClassFileBuilder;
import wyjvm.io.ClassFileWriter;
import wyjvm.lang.ClassFile;
import wyjvm.util.Continuations;
import wyjvm.util.DeadCodeElimination;
import wyjvm.util.Validation;

public class ClassWriter implements Transform {
	private ClassFileBuilder classBuilder;
	private File outputDirectory = null;
	private boolean validate = true;

	//FIXME: deadCode elimination is currently unsafe because the
	// LineNumberTable and Exceptions attributes do not deal with rewrites
	// properly.
	private boolean deadCode = false;

	private boolean continuations = true;
	
	public ClassWriter(ModuleLoader loader) {
		classBuilder = new ClassFileBuilder(loader, wyjc.Main.MAJOR_VERSION,
				wyjc.Main.MINOR_VERSION);
	}	
	
	public void setValidate(boolean flag) {
		validate = flag;
	}
	
	public void setDeadcode(boolean flag) {
		deadCode = flag;
	}
	
	public void setOutputDirectory(String dir) {		
		outputDirectory = new File(dir);
		if (!outputDirectory.exists()) {
			throw new RuntimeException("directory not found: " + dir);
		} else if (!outputDirectory.isDirectory()) {
			throw new RuntimeException("not a directory: " + dir);
		}
	}
	
	public void apply(Module m) throws IOException {		
		ClassFile file = classBuilder.build(m);		
		
		if(validate) {			
			// validate generated bytecode
			new Validation().apply(file);
		}
		if(deadCode) {
			// FIXME: deadCode elimination is currently unsafe because the
			// LineNumberTable and Exceptions attributes do not deal with rewrites
			// properly.
			
			// eliminate any dead code that was introduced.		
			new DeadCodeElimination().apply(file);
		}
		if (continuations) {
			new Continuations().apply(file);
		}
		// calculate filename
		String filename;
		if(outputDirectory == null) {
			// when no output directory is set, simply write back to the same
			// place the source file was in.
			filename = m.filename().replace(".whiley", ".class");
		} else {			
			filename = m.id().fileName().replace('.', File.separatorChar) + ".class";
			filename = outputDirectory + File.separator + filename;			
		}
		FileOutputStream out = new FileOutputStream(filename);		
		ClassFileWriter writer = new ClassFileWriter(out,null);			
		writer.write(file);					
	}	
	
}
