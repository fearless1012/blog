/*
 * Copyright (c) 2005, 2006, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in
 *   the documentation and/or other materials provided with the
 *   distribution.  
 *
 * * Neither the name of the University of California, Berkeley nor
 *   the names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior 
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package blog;

import java.util.*;
import java.io.PrintStream;

import blog.bn.BayesNetVar;
import blog.bn.RandFuncAppVar;
import blog.common.Util;
import blog.model.ArgSpec;
import blog.model.Model;
import blog.model.Term;

/**
 * Stores the information extracted from evidence file. The evidence is of 2
 * kinds:
 * <ul>
 * <li>symbol evidence statements, which introduce the objects of the specified
 * type that satisfy the stated condition;
 * <li>value evidence statements, which specify the value of a term.
 * </ul>
 * 
 * An Evidence object serves two purposes. First, it defines a set of Skolem
 * constants, which are additional constant symbols that can be used in the
 * evidence and query files. Second, it defines a set of evidence variables and
 * an observed value for each of these variables. The evidence variables may be
 * basic variables or other random variables created to represent the evidence.
 * For example, if we have a value evidence statement: <blockquote>
 * height(mother(John)) = 13.7 </blockquote> then the evidence object creates a
 * new random variable whose value is the value of the term
 * height(mother(John)).
 * 
 * @see blog.SymbolEvidenceStatement
 * @see blog.ValueEvidenceStatement
 */
public class Evidence {

	/**
	 * Creates a new Evidence object with no evidence.
	 */
	public Evidence() {

	}

	/**
	 * Creates an Evidence object out of a collection of statements.
	 */
	public static Evidence constructAndCompile(Collection statements) {
		Evidence result = new Evidence();
		Iterator it;
		for (it = statements.iterator(); it.hasNext();) {
			Object statement = it.next();
			if (statement instanceof ValueEvidenceStatement)
				result.addValueEvidence((ValueEvidenceStatement) statement);
			else
				result.addSymbolEvidence((SymbolEvidenceStatement) statement);
		}
		result.compile();
		return result;
	}

	public void addSymbolEvidence(SymbolEvidenceStatement sevid) {
		symbolEvidence.add(sevid);
		for (Iterator iter = sevid.getSkolemConstants().iterator(); iter.hasNext();) {
			SkolemConstant c = (SkolemConstant) iter.next();
			skolemConstants.add(c);
			skolemConstantsByName.put(c.getName(), c);
		}
	}

	/**
	 * Returns an unmodifiable Collection of SymbolEvidenceStatement objects.
	 */
	public Collection getSymbolEvidence() {
		return Collections.unmodifiableCollection(symbolEvidence);
	}

	public void addValueEvidence(ValueEvidenceStatement evid) {
		valueEvidence.add(evid);
		// TODO: add a 'isCompiled' method to statements so that
		// when compiled statements are added to empty evidence,
		// it is considered compiled as well.
	}

	/**
	 * Returns an unmodifiable Collection of ValueEvidenceStatement objects.
	 */
	public Collection getValueEvidence() {
		return Collections.unmodifiableCollection(valueEvidence);
	}

	/**
	 * Adds all symbol and value evidence statements from another evidence object.
	 */
	public void addAll(Evidence another) {
		for (Iterator it = another.getSymbolEvidence().iterator(); it.hasNext();) {
			addSymbolEvidence((SymbolEvidenceStatement) it.next());
		}
		for (Iterator it = another.getValueEvidence().iterator(); it.hasNext();) {
			addValueEvidence((ValueEvidenceStatement) it.next());
		}
		if (compiled || another.compiled)
			compile();
	}

	/**
	 * Returns the SkolemConstant object for the given symbol, or null if no such
	 * Skolem constant has been introduced.
	 */
	public SkolemConstant getSkolemConstant(String name) {
		return (SkolemConstant) skolemConstantsByName.get(name);
	}

	/**
	 * Returns an unmodifiable List of the SkolemConstant objects introduced by
	 * this evidence, in the order they were introduced.
	 */
	public List getSkolemConstants() {
		return Collections.unmodifiableList(skolemConstants);
	}

	/**
	 * Returns the set of evidence variables for which the user has observed
	 * values.
	 * 
	 * @return an unmodifiable Set of BayesNetVar objects
	 */
	public Set getEvidenceVars() {
		return Collections.unmodifiableSet(observedValues.keySet());
	}

	public Set getEvidenceVars(Timestep t) {
		Set evidenceAtTime = new HashSet();
		for (Iterator iter = observedValues.keySet().iterator(); iter.hasNext();) {
			BayesNetVar var = (BayesNetVar) iter.next();
			if (var instanceof RandFuncAppVar) {
				RandFuncAppVar rfav = (RandFuncAppVar) var;
				Timestep t_prime = rfav.timestep();
				if (t_prime == null) {
					if (t == null)
						evidenceAtTime.add(var);
				} else if (t_prime.equals(t))
					evidenceAtTime.add(var);
			}
		}
		return Collections.unmodifiableSet(evidenceAtTime);
	}

	/**
	 * Returns the observed value of the given variable.
	 * 
	 * @throws IllegalArgumentException
	 *           if no value has been observed for the given variable
	 */
	public Object getObservedValue(BayesNetVar var) {
		if (!observedValues.containsKey(var)) {
			throw new IllegalArgumentException("No observed value for " + var);
		}

		return observedValues.get(var);
	}

	/**
	 * Returns true if the given partial world is complete enough to determine
	 * whether this evidence is true or false.
	 */
	public boolean isDetermined(PartialWorld w) {
		for (Iterator iter = symbolEvidence.iterator(); iter.hasNext();) {
			SymbolEvidenceStatement stmt = (SymbolEvidenceStatement) iter.next();
			if (!stmt.isDetermined(w)) {
				return false;
			}
		}

		for (Iterator iter = valueEvidence.iterator(); iter.hasNext();) {
			ValueEvidenceStatement stmt = (ValueEvidenceStatement) iter.next();
			if (!stmt.isDetermined(w)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Returns true if this evidence is true in the given world; otherwise false.
	 */
	public boolean isTrue(PartialWorld w) {
		for (Iterator iter = symbolEvidence.iterator(); iter.hasNext();) {
			SymbolEvidenceStatement stmt = (SymbolEvidenceStatement) iter.next();
			if (!stmt.isTrue(w)) {
				return false;
			}
		}

		for (Iterator iter = valueEvidence.iterator(); iter.hasNext();) {
			ValueEvidenceStatement stmt = (ValueEvidenceStatement) iter.next();
			if (!stmt.isTrue(w)) {
				return false;
			}
		}

		return true;
	}

	/** Indicates whether evidence is empty or not. */
	public boolean isEmpty() {
		return getValueEvidence().isEmpty() && getSymbolEvidence().isEmpty();
	}

	/**
	 * Prints the evidence to the given stream.
	 */
	public void print(PrintStream s) {
		for (Iterator iter = symbolEvidence.iterator(); iter.hasNext();) {
			SymbolEvidenceStatement stmt = (SymbolEvidenceStatement) iter.next();
			System.out.println(stmt);
		}

		for (Iterator iter = valueEvidence.iterator(); iter.hasNext();) {
			ValueEvidenceStatement stmt = (ValueEvidenceStatement) iter.next();
			System.out.println(stmt);
		}
	}

	private void recordEvidence(BayesNetVar observedVar, Object observedValue,
			Object source) {
		if (observedValues.containsKey(observedVar)) {
			Object existingValue = observedValues.get(observedVar);
			if (!existingValue.equals(observedValue)) {
				Util.fatalError("Evidence \"" + source + "\" contradicts "
						+ "earlier evidence.", false);
			}
		} else {
			observedValues.put(observedVar, observedValue);
		}
	}

	public double setEvidenceEnsureSupportedAndReturnLikelihood(
			PartialWorld curWorld) {
		setEvidenceAndEnsureSupported(curWorld);
		return getEvidenceProb(curWorld);
	}

	public void setEvidenceAndEnsureSupported(PartialWorld curWorld) {
		BLOGUtil.setBasicVars(this, curWorld);
		BLOGUtil.ensureDetAndSupported(getEvidenceVars(), curWorld);
	}

	public double getEvidenceProb(PartialWorld curWorld) {
		return Math.exp(getEvidenceLogProb(curWorld));
	}

	public double getEvidenceProb(PartialWorld curWorld, Timestep t) {
		return Math.exp(getEvidenceLogProb(curWorld, t));
	}

	public double getEvidenceLogProb(PartialWorld curWorld) {
		return getEvidenceLogProb(curWorld, getEvidenceVars());
	}

	public double getEvidenceLogProb(PartialWorld curWorld, Timestep t) {
		return getEvidenceLogProb(curWorld, getEvidenceVars(t));
	}

	private double getEvidenceLogProb(PartialWorld curWorld, Set evidenceVars) {
		if (!compiled && !isEmpty())
			Util.fatalError("Trying to use evidence object that is not compiled yet.");

		double evidenceLogSum = 0;

		for (Iterator iter = evidenceVars.iterator(); iter.hasNext();) {
			BayesNetVar var = (BayesNetVar) iter.next();
			if (getObservedValue(var).equals(var.getValue(curWorld))) {
				evidenceLogSum += curWorld.getLogProbOfValue(var);
			} else {
				// The value of this variable in curWorld is not the
				// observed value.
				evidenceLogSum += Double.NEGATIVE_INFINITY;
				// implies that the actual probability is 0
			}
		}
		return evidenceLogSum;
	}

	/**
	 * Returns true if the evidence satisfies type and scope constraints. If there
	 * are type or scope errors, prints messages to standard error and returns
	 * false.
	 */
	public boolean checkTypesAndScope(Model model) {
		boolean correct = true;

		for (Iterator iter = symbolEvidence.iterator(); iter.hasNext();) {
			SymbolEvidenceStatement stmt = (SymbolEvidenceStatement) iter.next();
			if (!stmt.checkTypesAndScope(model)) {
				correct = false;
			}
		}

		for (Iterator iter = valueEvidence.iterator(); iter.hasNext();) {
			ValueEvidenceStatement stmt = (ValueEvidenceStatement) iter.next();
			if (!stmt.checkTypesAndScope(model)) {
				correct = false;
			}
		}

		return correct;
	}

	/**
	 * Does compilation steps that can only be done correctly once the model is
	 * complete. Prints messages to standard error if any errors are encountered.
	 * Returns the number of errors encountered.
	 */
	public int compile() {
		compiled = true;

		int errors = 0;
		LinkedHashSet callStack = new LinkedHashSet();

		for (Iterator iter = symbolEvidence.iterator(); iter.hasNext();) {
			SymbolEvidenceStatement stmt = (SymbolEvidenceStatement) iter.next();
			int thisStmtErrors = stmt.compile(callStack);
			if (thisStmtErrors == 0) {
				recordEvidence(stmt.getObservedVar(), stmt.getObservedValue(), stmt);
			}
			errors += thisStmtErrors;
		}

		for (Iterator iter = valueEvidence.iterator(); iter.hasNext();) {
			ValueEvidenceStatement stmt = (ValueEvidenceStatement) iter.next();
			int thisStmtErrors = stmt.compile(callStack);
			if (thisStmtErrors == 0) {
				recordEvidence(stmt.getObservedVar(), stmt.getObservedValue(), stmt);
			}
			errors += thisStmtErrors;
		}

		return errors;
	}

	public Evidence replace(Term t, ArgSpec another) {
		List newSymbolEvidence = new LinkedList();
		List newValueEvidence = new LinkedList();
		boolean replacement = false;
		for (Iterator it = getSymbolEvidence().iterator(); it.hasNext();) {
			SymbolEvidenceStatement ses = (SymbolEvidenceStatement) it.next();
			SymbolEvidenceStatement newSes = ses.replace(t, another);
			if (newSes != ses)
				replacement = true;
			newSymbolEvidence.add(newSes);
		}
		for (Iterator it = getValueEvidence().iterator(); it.hasNext();) {
			ValueEvidenceStatement ves = (ValueEvidenceStatement) it.next();
			ValueEvidenceStatement newVes = ves.replace(t, another);
			if (newVes != ves)
				replacement = true;
			newValueEvidence.add(newVes);
		}
		if (replacement) {
			Evidence newEvidence = new Evidence();
			newEvidence.valueEvidence.addAll(newValueEvidence);
			newEvidence.symbolEvidence.addAll(newSymbolEvidence);
			if (compiled)
				newEvidence.compile();
			return newEvidence;
		}
		return this;
	}

	public String toString() {
		List list = new LinkedList();
		list.addAll(getValueEvidence());
		list.addAll(getSymbolEvidence());
		return list.toString();
	}

	// List of SymbolEvidenceStatement
	private List symbolEvidence = new ArrayList();

	// List of ValueEvidenceStatement
	private List valueEvidence = new ArrayList();

	// map from String to SkolemConstant
	private Map skolemConstantsByName = new HashMap();

	// List of SkolemConstant objects in the order they were introduced
	private List skolemConstants = new ArrayList();

	// map from BayesNetVar to Object
	private Map observedValues = new LinkedHashMap();

	private boolean compiled = false;
}
