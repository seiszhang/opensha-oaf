package org.opensha.oaf.rj;

import org.opensha.oaf.util.MarshalReader;
import org.opensha.oaf.util.MarshalWriter;
import org.opensha.oaf.util.MarshalException;


/**
 * Magnitude-dependent search magnitude function -- Constant value with floor below mainshock.
 * Author: Michael Barall 07/27/2018.
 *
 * This class represents a magnitude that is constant, except that it is
 * limited to a maximum number of magnitudes below the mainshock.
 *
 * The magnitude is:
 *
 * max (mag, magMain - deltaMax)
 *
 * The purpose of the floor is to avoid requesting a very large number of
 * earthquakes from Comcat.
 */
public class SearchMagFnFloor extends SearchMagFn {

	//----- Parameters -----

	// Constant magnitude.

	private double mag;

	// Maximum number of magnitudes below mainshock, 0.0 means no floor.

	private double deltaMax;




	//----- Evaluation -----


	/**
	 * Calculate the magnitude-dependent search magnitude.
	 * @param magMain = Magnitude of mainshock.
	 * @return
	 * Returns the magnitude-dependent search magnitude.
	 * A return value <= NO_MIN_MAG means no lower limit (not recommended).
	 */
	@Override
	public double getMag (double magMain) {

		// Start with a constant

		double my_mag = mag;

		// If a maximum delta has been supplied, apply it

		if (deltaMax > 0.001) {
			my_mag = Math.max (my_mag, magMain - deltaMax);
		}
	
		return my_mag;
	}




	//----- Construction -----

	/**
	 * Default constructor does nothing.
	 */
	public SearchMagFnFloor () {}


	/**
	 * Construct from given parameters.
	 * @param mag = Constant magnitude.
	 * @param deltaMax = Minimum radius, 0.0 means no minimum.
	 */
	public SearchMagFnFloor (double mag, double deltaMax) {
		this.mag = mag;
		this.deltaMax = deltaMax;
	}


	// Display our contents

	@Override
	public String toString() {
		return "FnFloor[mag=" + mag
		+ ", deltaMax=" + deltaMax
		+ "]";
	}




	//----- Legacy methods -----


	/**
	 * [DEPRECATED]
	 * Get the "legacy" value of search magnitude.
	 * If the function can be represented in "legacy" format, return the legacy magnitude.
	 * Otherwise, throw an exception.
	 * This is for marshaling "legacy" files in formats used before SearchMagFn was created.
	 * The "legacy" format has a constant magnitude.
	 */
	@Override
	public double getLegacyMag () {
		if (deltaMax > 0.001) {
			throw new MarshalException ("SearchMagFn.getLegacyRadius: Function is not of legacy format.");
		}
		return mag;
	}




	//----- Marshaling -----

	// Marshal version number.

	private static final int MARSHAL_HWV_1 = 1;		// human-writeable version
	private static final int MARSHAL_VER_1 = 69001;

	private static final String M_VERSION_NAME = "SearchMagFnFloor";

	// Get the type code.

	@Override
	protected int get_marshal_type () {
		return MARSHAL_FLOOR;
	}

	// Marshal object, internal.

	@Override
	protected void do_marshal (MarshalWriter writer) {

		// Version

		writer.marshalInt (M_VERSION_NAME, MARSHAL_VER_1);

		// Superclass

		super.do_marshal (writer);

		// Contents

		writer.marshalDouble ("mag"       , mag       );
		writer.marshalDouble ("deltaMax"  , deltaMax  );

		return;
	}

	// Unmarshal object, internal.

	@Override
	protected void do_umarshal (MarshalReader reader) {
	
		// Version

		int ver = reader.unmarshalInt (M_VERSION_NAME);

		switch (ver) {

		default:
			throw new MarshalException ("SearchMagFnFloor.do_umarshal: Unknown version code: version = " + ver);
		
		// Human-writeable version

		case MARSHAL_HWV_1: {

			// Get parameters

			mag        = reader.unmarshalDouble ("mag"       );
			deltaMax   = reader.unmarshalDouble ("deltaMax"  );
		}
		break;

		// Machine-written version

		case MARSHAL_VER_1: {

			// Superclass

			super.do_umarshal (reader);

			// Contents

			mag        = reader.unmarshalDouble ("mag"       );
			deltaMax   = reader.unmarshalDouble ("deltaMax"  );
		}
		break;

		}

		return;
	}

	// Marshal object.

	@Override
	public void marshal (MarshalWriter writer, String name) {
		writer.marshalMapBegin (name);
		do_marshal (writer);
		writer.marshalMapEnd ();
		return;
	}

	// Unmarshal object.

	@Override
	public SearchMagFnFloor unmarshal (MarshalReader reader, String name) {
		reader.unmarshalMapBegin (name);
		do_umarshal (reader);
		reader.unmarshalMapEnd ();
		return this;
	}

	// Marshal object, polymorphic.

	public static void marshal_poly (MarshalWriter writer, String name, SearchMagFnFloor obj) {

		writer.marshalMapBegin (name);

		if (obj == null) {
			writer.marshalInt (M_TYPE_NAME, MARSHAL_NULL);
		} else {
			writer.marshalInt (M_TYPE_NAME, obj.get_marshal_type());
			obj.do_marshal (writer);
		}

		writer.marshalMapEnd ();

		return;
	}

	// Unmarshal object, polymorphic.

	public static SearchMagFnFloor unmarshal_poly (MarshalReader reader, String name) {
		SearchMagFnFloor result;

		reader.unmarshalMapBegin (name);
	
		// Switch according to type

		int type = reader.unmarshalInt (M_TYPE_NAME);

		switch (type) {

		default:
			throw new MarshalException ("SearchMagFnFloor.unmarshal_poly: Unknown class type code: type = " + type);

		case MARSHAL_NULL:
			result = null;
			break;

		case MARSHAL_FLOOR:
			result = new SearchMagFnFloor();
			result.do_umarshal (reader);
			break;
		}

		reader.unmarshalMapEnd ();

		return result;
	}

}
