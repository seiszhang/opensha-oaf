package scratch.aftershockStatistics.gamma;

import java.util.List;
import java.util.Arrays;

import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupList;
import org.opensha.sha.earthquake.observedEarthquake.ObsEqkRupture;

import scratch.aftershockStatistics.ComcatAccessor;
import scratch.aftershockStatistics.AftershockStatsCalc;
import scratch.aftershockStatistics.RJ_AftershockModel;

import scratch.aftershockStatistics.aafs.ForecastMainshock;
import scratch.aftershockStatistics.aafs.ForecastParameters;
import scratch.aftershockStatistics.aafs.ForecastResults;

import scratch.aftershockStatistics.util.SimpleUtils;
import scratch.aftershockStatistics.util.MarshalReader;
import scratch.aftershockStatistics.util.MarshalWriter;
import scratch.aftershockStatistics.util.MarshalException;


/**
 * A set of log-likelihood setss for an earthquake.
 * Author: Michael Barall 10/10/2018.
 *
 * This object holds a set of log-likelihood sets for a single earthquake.
 * There is one log-likelihood set for each forecast lag and aftershock model.
 */
public class EqkForecastSet {

	//----- Data -----

	// Number of simulations.

	private int num_sim;

	// Information about the mainshock.

	private ForecastMainshock fcmain;

	// Array of log-likelihood sets for the earthquake.
	// Dimension of the array is
	//  obs_log_like[forecast_lag_count][model_kind_count].
	
	private LogLikeSet[][] log_like_sets;




	//----- Construction -----

	// Constructor creates an empty object.

	public EqkForecastSet () {
		fcmain = null;
		num_sim = -1;
		log_like_sets = null;
	}




	// Run simulations.
	// Parameters:
	//  gamma_config = Configuration information.
	//  the_num_sim = The number of simulations to run.
	//  the_fcmain = Mainshock information.
	//  verbose = True to write output for each simulation.

	public void run_simulations (GammaConfig gamma_config, int the_num_sim,
		ForecastMainshock the_fcmain, boolean verbose) {

		// Save number of simulations and mainshock information

		num_sim = the_num_sim;
		fcmain = the_fcmain;

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Allocate all the arrays

		log_like_sets = new LogLikeSet[num_fc_lag][num_model];

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				log_like_sets[i_fc_lag][i_model] = new LogLikeSet();
			}
		}

		// Get catalog of all aftershocks

		List<ObsEqkRupture> all_aftershocks = ProbDistSet.get_all_aftershocks (gamma_config, fcmain);

		// Loop over forecast lags ...

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {

			// Get the forecast lag

			long forecast_lag = gamma_config.forecast_lags[i_fc_lag];

			// Get parameters

			ForecastParameters params = new ForecastParameters();
			params.fetch_all_params (forecast_lag, fcmain, null);

			// Get results

			ForecastResults results = new ForecastResults();
			results.calc_all (fcmain.mainshock_time + forecast_lag, ForecastResults.ADVISORY_LAG_WEEK, "", fcmain, params, true);

			if (!( results.generic_result_avail
				&& results.seq_spec_result_avail
				&& results.bayesian_result_avail )) {
				throw new RuntimeException ("EqkForecastSet: Failed to compute aftershock models");
			}

			// Generic model

			log_like_sets[i_fc_lag][GammaConfig.MODEL_KIND_GENERIC].run_simulations (
				gamma_config, forecast_lag, num_sim,
				fcmain, results.generic_model, all_aftershocks, verbose);

			// Sequence specific model

			log_like_sets[i_fc_lag][GammaConfig.MODEL_KIND_SEQ_SPEC].run_simulations (
				gamma_config, forecast_lag, num_sim,
				fcmain, results.seq_spec_model, all_aftershocks, verbose);

			// Bayesian model

			log_like_sets[i_fc_lag][GammaConfig.MODEL_KIND_BAYESIAN].run_simulations (
				gamma_config, forecast_lag, num_sim,
				fcmain, results.bayesian_model, all_aftershocks, verbose);
		}

		return;
	}




	// Allocate and zero-initialize all arrays.
	// Parameters:
	//  gamma_config = Configuration information.
	//  the_num_sim = The number of simulations to run.

	public void zero_init (GammaConfig gamma_config, int the_num_sim) {

		// Save number of simulations and mainshock information

		num_sim = the_num_sim;
		fcmain = null;

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Allocate all the arrays and zero-initialize

		log_like_sets = new LogLikeSet[num_fc_lag][num_model];

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
			long forecast_lag = gamma_config.forecast_lags[i_fc_lag];
			for (int i_model = 0; i_model < num_model; ++i_model) {
				log_like_sets[i_fc_lag][i_model] = new LogLikeSet();
				log_like_sets[i_fc_lag][i_model].zero_init (gamma_config, forecast_lag, num_sim);
			}
		}

		return;
	}




	// Add array contents from another object into this object.
	// Parameters:
	//  gamma_config = Configuration information.
	//  other = The other object.
	//  randomize = True to select simulations from the other object randomly.

	public void add_from (GammaConfig gamma_config, EqkForecastSet other, boolean randomize) {

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Add each array element

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				log_like_sets[i_fc_lag][i_model].add_from (
					gamma_config, other.log_like_sets[i_fc_lag][i_model], randomize);
			}
		}

		return;
	}




	//----- Querying -----

	// Compute the single-event gamma.
	// Parameters:
	//  gamma_config = Configuration information.
	//  gamma_lo = Array to receive low value of gamma, dimension:
	//    gamma_lo[forecast_lag_count + 1][model_kind_count][adv_window_count + 1][adv_min_mag_bin_count].
	//  gamma_hi = Array to receive high value of gamma, dimension:
	//    gamma_hi[forecast_lag_count + 1][model_kind_count][adv_window_count + 1][adv_min_mag_bin_count].
	// The extra forecast lag slot is used to report the sum over all forecast lags.
	// The extra advisory window slot is used to report the sum over all windows.

	public void single_event_gamma (GammaConfig gamma_config, double[][][][] gamma_lo, double[][][][] gamma_hi) {

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Loop over forecast lags and aftershock models, computing gamma for each

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				log_like_sets[i_fc_lag][i_model].single_event_gamma (gamma_config,
					gamma_lo[i_fc_lag][i_model], gamma_hi[i_fc_lag][i_model]);
			}
		}

		// Loop over models, computing gamma for sum over forecast lags

		for (int i_model = 0; i_model < num_model; ++i_model) {

			// Accumulate sum

			LogLikeSet sum = new LogLikeSet();
			sum.zero_init (gamma_config, -1L, num_sim);

			for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
				sum.add_from (gamma_config, log_like_sets[i_fc_lag][i_model], false);
			}

			// Compute gamma

			sum.single_event_gamma (gamma_config,
				gamma_lo[num_fc_lag][i_model], gamma_hi[num_fc_lag][i_model]);
		}

		return;
	}




	// Compute event count statistics.
	// Parameters:
	//  gamma_config = Configuration information.
	//  obs_count = Array to receive observed count, dimension:
	//    obs_count[forecast_lag_count + 1][model_kind_count][adv_window_count][adv_min_mag_bin_count].
	//  sim_median_count = Array to receive simulated median count, dimension:
	//    sim_median_count[forecast_lag_count + 1][model_kind_count][adv_window_count][adv_min_mag_bin_count].
	//  sim_fractile_5_count = Array to receive simulated 5 percent fractile count, dimension:
	//    sim_fractile_5_count[forecast_lag_count + 1][model_kind_count][adv_window_count][adv_min_mag_bin_count].
	//  sim_fractile_95_count = Array to receive simulated 95 percent fractile count, dimension:
	//    sim_fractile_95_count[forecast_lag_count + 1][model_kind_count][adv_window_count][adv_min_mag_bin_count].
	// The extra forecast lag slot is used to report the sum over all forecast lags.

	public void compute_count_stats (GammaConfig gamma_config, int[][][][] obs_count,
		int[][][][] sim_median_count, int[][][][] sim_fractile_5_count, int[][][][] sim_fractile_95_count) {

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Loop over forecast lags and aftershock models, computing statistics for each

		for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				log_like_sets[i_fc_lag][i_model].compute_count_stats (gamma_config,
					obs_count[i_fc_lag][i_model], sim_median_count[i_fc_lag][i_model],
					sim_fractile_5_count[i_fc_lag][i_model], sim_fractile_95_count[i_fc_lag][i_model]);
			}
		}

		// Loop over models, computing statistics for sum over forecast lags

		for (int i_model = 0; i_model < num_model; ++i_model) {

			// Accumulate sum

			LogLikeSet sum = new LogLikeSet();
			sum.zero_init (gamma_config, -1L, num_sim);

			for (int i_fc_lag = 0; i_fc_lag < num_fc_lag; ++i_fc_lag) {
				sum.add_from (gamma_config, log_like_sets[i_fc_lag][i_model], false);
			}

			// Compute statistics

			sum.compute_count_stats (gamma_config,
				obs_count[num_fc_lag][i_model], sim_median_count[num_fc_lag][i_model],
				sim_fractile_5_count[num_fc_lag][i_model], sim_fractile_95_count[num_fc_lag][i_model]);
		}

		return;
	}




	// Compute the single-event gamma, and convert the table to a string.
	// Parameters:
	//  gamma_config = Configuration information.

	public String single_event_gamma_to_string (GammaConfig gamma_config) {

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Number of advisory windows and magnitude bins

		int num_adv_win = gamma_config.adv_window_count;
		int num_mag_bin = gamma_config.adv_min_mag_bin_count;

		// Compute results

		double[][][][] gamma_lo = new double[num_fc_lag + 1][num_model][num_adv_win + 1][num_mag_bin];
		double[][][][] gamma_hi = new double[num_fc_lag + 1][num_model][num_adv_win + 1][num_mag_bin];

		single_event_gamma (gamma_config, gamma_lo, gamma_hi);

		// Convert the table

		StringBuilder sb = new StringBuilder();
		for (int i_fc_lag = 0; i_fc_lag <= num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				for (int i_adv_win = 0; i_adv_win <= num_adv_win; ++i_adv_win) {
					for (int i_mag_bin = 0; i_mag_bin < num_mag_bin; ++i_mag_bin) {
						sb.append (
							gamma_config.get_forecast_lag_string_or_sum(i_fc_lag) + ",  "
							+ gamma_config.model_kind_to_string(i_model) + ",  "
							+ gamma_config.get_adv_window_name_or_sum(i_adv_win) + ",  "
							+ "mag = " + gamma_config.adv_min_mag_bins[i_mag_bin] + ",  "
							+ "gamma_lo = " + gamma_lo[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
							+ "gamma_hi = " + gamma_hi[i_fc_lag][i_model][i_adv_win][i_mag_bin] + "\n"
						);
					}
				}
			}
		}

		return sb.toString();
	}




	// Compute event count statistics, and convert the table to a string.
	// Parameters:
	//  gamma_config = Configuration information.

	public String compute_count_stats_to_string (GammaConfig gamma_config) {

		// Number of forecast lags and aftershock models

		int num_fc_lag = gamma_config.forecast_lag_count;
		int num_model = gamma_config.model_kind_count;

		// Number of advisory windows and magnitude bins

		int num_adv_win = gamma_config.adv_window_count;
		int num_mag_bin = gamma_config.adv_min_mag_bin_count;

		// Compute results

		int[][][][] obs_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
		int[][][][] sim_median_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
		int[][][][] sim_fractile_5_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
		int[][][][] sim_fractile_95_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];

		compute_count_stats (gamma_config, obs_count,
			sim_median_count, sim_fractile_5_count, sim_fractile_95_count);

		// Convert the table

		StringBuilder sb = new StringBuilder();
		for (int i_fc_lag = 0; i_fc_lag <= num_fc_lag; ++i_fc_lag) {
			for (int i_model = 0; i_model < num_model; ++i_model) {
				for (int i_adv_win = 0; i_adv_win < num_adv_win; ++i_adv_win) {
					for (int i_mag_bin = 0; i_mag_bin < num_mag_bin; ++i_mag_bin) {
						sb.append (
							gamma_config.get_forecast_lag_string_or_sum(i_fc_lag) + ",  "
							+ gamma_config.model_kind_to_string(i_model) + ",  "
							+ gamma_config.adv_window_names[i_adv_win] + ",  "
							+ "mag = " + gamma_config.adv_min_mag_bins[i_mag_bin] + ",  "
							+ "obs = " + obs_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
							+ "median = " + sim_median_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
							+ "fractile_5 = " + sim_fractile_5_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
							+ "fractile_95 = " + sim_fractile_95_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + "\n"
						);
					}
				}
			}
		}

		return sb.toString();
	}




	//----- Marshaling -----

	// Marshal version number.

	private static final int MARSHAL_VER_1 = 48001;

	private static final String M_VERSION_NAME = "EqkForecastSet";

	// Marshal type code.

	protected static final int MARSHAL_NULL = 48000;
	protected static final int MARSHAL_EQK_FORECAST_SET = 48001;

	protected static final String M_TYPE_NAME = "ClassType";

	// Get the type code.

	protected int get_marshal_type () {
		return MARSHAL_EQK_FORECAST_SET;
	}

	// Marshal object, internal.

	protected void do_marshal (MarshalWriter writer) {

		// Version

		writer.marshalInt (M_VERSION_NAME, MARSHAL_VER_1);

		// Contents

		writer.marshalInt                (        "num_sim"        , num_sim        );
		ForecastMainshock.marshal_poly   (writer, "fcmain"         , fcmain         );
		LogLikeSet.marshal_2d_array_poly (writer, "log_like_sets"  , log_like_sets  );
	
		return;
	}

	// Unmarshal object, internal.

	protected void do_umarshal (MarshalReader reader) {
	
		// Version

		int ver = reader.unmarshalInt (M_VERSION_NAME, MARSHAL_VER_1, MARSHAL_VER_1);

		// Contents

		num_sim         = reader.unmarshalInt                (        "num_sim"        );
		fcmain          = ForecastMainshock.unmarshal_poly   (reader, "fcmain"         );
		log_like_sets   = LogLikeSet.unmarshal_2d_array_poly (reader, "log_like_sets"  );

		return;
	}

	// Marshal object.

	public void marshal (MarshalWriter writer, String name) {
		writer.marshalMapBegin (name);
		do_marshal (writer);
		writer.marshalMapEnd ();
		return;
	}

	// Unmarshal object.

	public EqkForecastSet unmarshal (MarshalReader reader, String name) {
		reader.unmarshalMapBegin (name);
		do_umarshal (reader);
		reader.unmarshalMapEnd ();
		return this;
	}

	// Marshal object, polymorphic.

	public static void marshal_poly (MarshalWriter writer, String name, EqkForecastSet obj) {

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

	public static EqkForecastSet unmarshal_poly (MarshalReader reader, String name) {
		EqkForecastSet result;

		reader.unmarshalMapBegin (name);
	
		// Switch according to type

		int type = reader.unmarshalInt (M_TYPE_NAME);

		switch (type) {

		default:
			throw new MarshalException ("EqkForecastSet.unmarshal_poly: Unknown class type code: type = " + type);

		case MARSHAL_NULL:
			result = null;
			break;

		case MARSHAL_EQK_FORECAST_SET:
			result = new EqkForecastSet();
			result.do_umarshal (reader);
			break;
		}

		reader.unmarshalMapEnd ();

		return result;
	}

	// Marshal an array of objects, polymorphic.

	public static void marshal_array_poly (MarshalWriter writer, String name, EqkForecastSet[] x) {
		int n = x.length;
		writer.marshalArrayBegin (name, n);
		for (int i = 0; i < n; ++i) {
			marshal_poly (writer, null, x[i]);
		}
		writer.marshalArrayEnd ();
		return;
	}

	// Marshal a 2D array of objects, polymorphic.

	public static void marshal_2d_array_poly (MarshalWriter writer, String name, EqkForecastSet[][] x) {
		int n = x.length;
		writer.marshalArrayBegin (name, n);
		for (int i = 0; i < n; ++i) {
			marshal_array_poly (writer, null, x[i]);
		}
		writer.marshalArrayEnd ();
		return;
	}

	// Unmarshal an array of objects, polymorphic.

	public static EqkForecastSet[] unmarshal_array_poly (MarshalReader reader, String name) {
		int n = reader.unmarshalArrayBegin (name);
		EqkForecastSet[] x = new EqkForecastSet[n];
		for (int i = 0; i < n; ++i) {
			x[i] = unmarshal_poly (reader, null);
		}
		reader.unmarshalArrayEnd ();
		return x;
	}

	// Unmarshal a 2d array of objects, polymorphic.

	public static EqkForecastSet[][] unmarshal_2d_array_poly (MarshalReader reader, String name) {
		int n = reader.unmarshalArrayBegin (name);
		EqkForecastSet[][] x = new EqkForecastSet[n][];
		for (int i = 0; i < n; ++i) {
			x[i] = unmarshal_array_poly (reader, null);
		}
		reader.unmarshalArrayEnd ();
		return x;
	}




	//----- Testing -----

	// Entry point.
	
	public static void main(String[] args) {

		// There needs to be at least one argument, which is the subcommand

		if (args.length < 1) {
			System.err.println ("EqkForecastSet : Missing subcommand");
			return;
		}




		// Subcommand : Test #1
		// Command format:
		//  test1  event_id
		// Compute all models at all forecast lags for the given event.

		if (args[0].equalsIgnoreCase ("test1")) {

			// One additional argument

			if (args.length != 2) {
				System.err.println ("EqkForecastSet : Invalid 'test1' subcommand");
				return;
			}

			String the_event_id = args[1];

			// Get configuration

			GammaConfig gamma_config = new GammaConfig();

			// Number of forecast lags and aftershock models

			int num_fc_lag = gamma_config.forecast_lag_count;
			int num_model = gamma_config.model_kind_count;

			// Number of advisory windows and magnitude bins

			int num_adv_win = gamma_config.adv_window_count;
			int num_mag_bin = gamma_config.adv_min_mag_bin_count;

			// Fetch the mainshock info

			ForecastMainshock fcmain = new ForecastMainshock();
			fcmain.setup_mainshock_only (the_event_id);

			System.out.println ("");
			System.out.println (fcmain.toString());

			// Compute models

			System.out.println ("");
			System.out.println ("Computing models for event_id = " + the_event_id);

			EqkForecastSet eqk_forecast_set = new EqkForecastSet();
			eqk_forecast_set.run_simulations (gamma_config,
				gamma_config.simulation_count, fcmain, false);

			double[][][][] gamma_lo = new double[num_fc_lag + 1][num_model][num_adv_win + 1][num_mag_bin];
			double[][][][] gamma_hi = new double[num_fc_lag + 1][num_model][num_adv_win + 1][num_mag_bin];

			eqk_forecast_set.single_event_gamma (gamma_config, gamma_lo, gamma_hi);

			System.out.println ("");
			for (int i_fc_lag = 0; i_fc_lag <= num_fc_lag; ++i_fc_lag) {
				for (int i_model = 0; i_model < num_model; ++i_model) {
					for (int i_adv_win = 0; i_adv_win <= num_adv_win; ++i_adv_win) {
						for (int i_mag_bin = 0; i_mag_bin < num_mag_bin; ++i_mag_bin) {
							System.out.println (
								gamma_config.get_forecast_lag_string_or_sum(i_fc_lag) + ",  "
								+ gamma_config.model_kind_to_string(i_model) + ",  "
								+ gamma_config.get_adv_window_name_or_sum(i_adv_win) + ",  "
								+ "mag = " + gamma_config.adv_min_mag_bins[i_mag_bin] + ",  "
								+ "gamma_lo = " + gamma_lo[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
								+ "gamma_hi = " + gamma_hi[i_fc_lag][i_model][i_adv_win][i_mag_bin]
							);
						}
					}
				}
			}

			int[][][][] obs_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
			int[][][][] sim_median_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
			int[][][][] sim_fractile_5_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];
			int[][][][] sim_fractile_95_count = new int[num_fc_lag + 1][num_model][num_adv_win][num_mag_bin];

			eqk_forecast_set.compute_count_stats (gamma_config, obs_count,
				sim_median_count, sim_fractile_5_count, sim_fractile_95_count);

			System.out.println ("");
			for (int i_fc_lag = 0; i_fc_lag <= num_fc_lag; ++i_fc_lag) {
				for (int i_model = 0; i_model < num_model; ++i_model) {
					for (int i_adv_win = 0; i_adv_win < num_adv_win; ++i_adv_win) {
						for (int i_mag_bin = 0; i_mag_bin < num_mag_bin; ++i_mag_bin) {
							System.out.println (
								gamma_config.get_forecast_lag_string_or_sum(i_fc_lag) + ",  "
								+ gamma_config.model_kind_to_string(i_model) + ",  "
								+ gamma_config.adv_window_names[i_adv_win] + ",  "
								+ "mag = " + gamma_config.adv_min_mag_bins[i_mag_bin] + ",  "
								+ "obs = " + obs_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
								+ "median = " + sim_median_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
								+ "fractile_5 = " + sim_fractile_5_count[i_fc_lag][i_model][i_adv_win][i_mag_bin] + ",  "
								+ "fractile_95 = " + sim_fractile_95_count[i_fc_lag][i_model][i_adv_win][i_mag_bin]
							);
						}
					}
				}
			}

			return;
		}




		// Unrecognized subcommand.

		System.err.println ("EqkForecastSet : Unrecognized subcommand : " + args[0]);
		return;
	}
}